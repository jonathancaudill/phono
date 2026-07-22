package com.lightphone.spotify.playback

import android.content.Context
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Network quality tier for opportunistic buffering decisions. */
enum class NetworkTier {
    OFFLINE,
    POOR,
    FAIR,
    GOOD_METERED,
    GOOD_UNMETERED,
}

/**
 * Centralizes when to bank the current track and prefetch upcoming tracks.
 * Uses hysteresis so tier flapping on cellular does not thrash prefetch depth.
 *
 * Wi‑Fi preference gate: while on cellular, a brief Wi‑Fi appearance must stay
 * continuously visible for [WIFI_PREFER_AFTER_MS] before we treat the path as
 * unmetered Wi‑Fi (or allow a cellular→Wi‑Fi session handoff). All other tier
 * rules are unchanged.
 */
class StreamingPolicy(
    private val controller: PlaybackController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val context: Context get() = controller.appContextInternal

    @Volatile
    private var stableTier: NetworkTier = NetworkTier.FAIR

    private var tierUpCount = 0
    private var tierDownCount = 0

    /**
     * ElapsedRealtime when the current continuous Wi‑Fi/Ethernet visibility
     * window started; null when Wi‑Fi is not in the active capabilities.
     */
    @Volatile
    private var wifiVisibleSinceElapsedMs: Long? = null

    @Volatile
    private var lastCaps: NetworkCapabilities? = null

    private var wifiPreferJob: Job? = null

    fun onCapabilitiesChanged(caps: NetworkCapabilities) {
        lastCaps = caps
        updateWifiVisibility(caps)
        applyCaps(caps)
    }

    fun onOffline() {
        wifiPreferJob?.cancel()
        wifiPreferJob = null
        lastCaps = null
        stableTier = NetworkTier.OFFLINE
        tierUpCount = 0
        tierDownCount = 0
        wifiVisibleSinceElapsedMs = null
    }

    fun onTrackActive() {
        if (!controller.state.value.isPlaying) return
        maybeBufferOpportunistically()
    }

    fun onPlaybackStall() {
        if (isBatteryConstrained()) return
        bankCurrentTrack()
    }

    fun currentTier(): NetworkTier = stableTier

    /**
     * True when Wi‑Fi/Ethernet has been continuously present long enough that
     * we should prefer it over cellular (tier + transport handoff).
     */
    fun shouldPreferWifi(caps: NetworkCapabilities): Boolean {
        updateWifiVisibility(caps)
        if (!isWifiOrEthernet(caps)) return false
        val since = wifiVisibleSinceElapsedMs ?: return false
        return SystemClock.elapsedRealtime() - since >= WIFI_PREFER_AFTER_MS
    }

    fun prefetchDepth(): Int = when (stableTier) {
        NetworkTier.GOOD_UNMETERED -> 3
        NetworkTier.GOOD_METERED -> 2
        // Even on a weak connection, prefetch the single next track (predictive
        // skip target) — but only AFTER the current track is banked (see
        // maybeBufferOpportunistically ordering).
        NetworkTier.FAIR, NetworkTier.POOR -> 1
        else -> 0
    }

    private fun applyCaps(caps: NetworkCapabilities) {
        val raw = classify(caps)
        var upgraded = false
        when {
            raw.ordinal > stableTier.ordinal -> {
                tierDownCount = 0
                tierUpCount++
                if (tierUpCount >= TIER_UP_SAMPLES) {
                    stableTier = raw
                    tierUpCount = 0
                    upgraded = true
                }
            }
            raw.ordinal < stableTier.ordinal -> {
                tierUpCount = 0
                tierDownCount++
                if (tierDownCount >= TIER_DOWN_SAMPLES) {
                    stableTier = raw
                    tierDownCount = 0
                }
            }
            else -> {
                tierUpCount = 0
                tierDownCount = 0
            }
        }
        // A committed tier upgrade means the connection just got healthier — warm
        // the session proactively so the next skip resolves against a live session
        // instead of paying for a cold rebuild.
        if (upgraded && stableTier != NetworkTier.OFFLINE) {
            controller.warmSpclientSessionAsync()
        }
        if (raw != NetworkTier.OFFLINE && controller.state.value.isPlaying) {
            maybeBufferOpportunistically()
        }
    }

    private fun maybeBufferOpportunistically() {
        if (isBatteryConstrained()) return
        if (stableTier == NetworkTier.OFFLINE) return
        scope.launch {
            // Bank the current track to its end FIRST so a mid-track disconnect
            // never stalls playback, THEN prefetch the predictive next target(s).
            bankCurrentTrack()
            val ahead = prefetchDepth()
            if (ahead > 0) {
                controller.prefetchUpcoming(ahead)
            }
        }
    }

    private fun bankCurrentTrack() {
        controller.bufferCurrentToEnd()
    }

    private fun isBatteryConstrained(): Boolean {
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm?.isPowerSaveMode == true) return true
        val bm = context.getSystemService(BatteryManager::class.java) ?: return false
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) in 0..14
    }

    private fun updateWifiVisibility(caps: NetworkCapabilities) {
        if (isWifiOrEthernet(caps)) {
            if (wifiVisibleSinceElapsedMs == null) {
                wifiVisibleSinceElapsedMs = SystemClock.elapsedRealtime()
                scheduleWifiPreferRecheck()
            }
        } else {
            wifiPreferJob?.cancel()
            wifiPreferJob = null
            wifiVisibleSinceElapsedMs = null
        }
    }

    /**
     * Capabilities may not fire again for a stable Wi‑Fi link — wake after the
     * gate so we can promote to GOOD_UNMETERED / allow handoff.
     */
    private fun scheduleWifiPreferRecheck() {
        wifiPreferJob?.cancel()
        wifiPreferJob = scope.launch {
            delay(WIFI_PREFER_AFTER_MS)
            val caps = lastCaps ?: return@launch
            if (!isWifiOrEthernet(caps)) return@launch
            if (!shouldPreferWifi(caps)) return@launch
            applyCaps(caps)
            // Let the controller re-evaluate cellular→Wi‑Fi handoff now that the
            // gate has elapsed (no-op if already on Wi‑Fi or not playing).
            controller.onWifiPreferGateElapsed(caps)
        }
    }

    private fun classify(caps: NetworkCapabilities): NetworkTier {
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return NetworkTier.OFFLINE
        }
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            return NetworkTier.POOR
        }
        val downKbps = caps.linkDownstreamBandwidthKbps
        val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        // Prefer Wi‑Fi as GOOD_UNMETERED only after the stability gate; brief
        // Wi‑Fi blips while on cellular keep the bandwidth/metered ladder.
        if (unmetered && isWifiOrEthernet(caps) && shouldPreferWifi(caps)) {
            return NetworkTier.GOOD_UNMETERED
        }
        return when {
            downKbps >= 1200 -> NetworkTier.GOOD_METERED
            downKbps >= 400 -> NetworkTier.FAIR
            else -> NetworkTier.POOR
        }
    }

    private fun isWifiOrEthernet(caps: NetworkCapabilities): Boolean =
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

    companion object {
        private const val TIER_UP_SAMPLES = 3
        private const val TIER_DOWN_SAMPLES = 2

        /** Wi‑Fi must stay visible this long before we prefer it over cellular. */
        const val WIFI_PREFER_AFTER_MS = 2 * 60 * 1000L
    }
}
