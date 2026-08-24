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
 * continuously visible for [WIFI_PREFER_AFTER_MS] (30s) before we treat the path as
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
        controller.publishNetworkTierHint()
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

    fun prefetchDepth(): Int = prefetchAhead(stableTier)

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
        controller.publishNetworkTierHint()
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
            // awaitBankIdle makes this ordering real (not fire-and-forget).
            bankCurrentTrack()
            controller.awaitBankIdle(BANK_AWAIT_MS)
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
        updateWifiVisibility(caps)
        val since = wifiVisibleSinceElapsedMs
        val visibleMs = if (since != null) SystemClock.elapsedRealtime() - since else 0L
        return classifyLink(
            LinkSnapshot(
                hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                wifiOrEthernet = isWifiOrEthernet(caps),
                unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                downKbps = caps.linkDownstreamBandwidthKbps,
                wifiVisibleMs = visibleMs,
            ),
        )
    }

    private fun isWifiOrEthernet(caps: NetworkCapabilities): Boolean =
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

    companion object {
        internal const val TIER_UP_SAMPLES = 3
        internal const val TIER_DOWN_SAMPLES = 2

        /** Wi‑Fi must stay visible this long before we prefer it over cellular. */
        const val WIFI_PREFER_AFTER_MS = 30_000L

        /**
         * Max wait for current-track bank before look-ahead. Cap so a hung CDN
         * does not block prefetch forever; policy still prefers bank-first.
         */
        const val BANK_AWAIT_MS = 25_000L

        fun prefetchAhead(tier: NetworkTier): Int = when (tier) {
            NetworkTier.GOOD_UNMETERED -> 3
            NetworkTier.GOOD_METERED -> 2
            NetworkTier.FAIR, NetworkTier.POOR -> 1
            NetworkTier.OFFLINE -> 0
        }

        fun classifyLink(link: LinkSnapshot): NetworkTier {
            if (!link.hasInternet) return NetworkTier.OFFLINE
            if (!link.validated) return NetworkTier.POOR
            if (
                link.unmetered &&
                link.wifiOrEthernet &&
                link.wifiVisibleMs >= WIFI_PREFER_AFTER_MS
            ) {
                return NetworkTier.GOOD_UNMETERED
            }
            return when {
                link.downKbps >= 1200 -> NetworkTier.GOOD_METERED
                link.downKbps >= 400 -> NetworkTier.FAIR
                else -> NetworkTier.POOR
            }
        }
    }
}

/** Inputs to [StreamingPolicy.classifyLink] so tests do not need NetworkCapabilities. */
data class LinkSnapshot(
    val hasInternet: Boolean,
    val validated: Boolean,
    val wifiOrEthernet: Boolean,
    val unmetered: Boolean,
    val downKbps: Int,
    val wifiVisibleMs: Long,
)

/**
 * Commit a raw classification into a stable tier. Matches the 3-up / 2-down
 * sample counts in [StreamingPolicy] so cellular flap does not thrash prefetch.
 */
data class NetworkTierHysteresis(
    val stable: NetworkTier = NetworkTier.FAIR,
    val upCount: Int = 0,
    val downCount: Int = 0,
) {
    fun observe(raw: NetworkTier): Pair<NetworkTierHysteresis, Boolean> {
        return when {
            raw.ordinal > stable.ordinal -> {
                val ups = upCount + 1
                if (ups >= StreamingPolicy.TIER_UP_SAMPLES) {
                    NetworkTierHysteresis(stable = raw) to true
                } else {
                    copy(upCount = ups, downCount = 0) to false
                }
            }
            raw.ordinal < stable.ordinal -> {
                val downs = downCount + 1
                if (downs >= StreamingPolicy.TIER_DOWN_SAMPLES) {
                    NetworkTierHysteresis(stable = raw) to false
                } else {
                    copy(downCount = downs, upCount = 0) to false
                }
            }
            else -> NetworkTierHysteresis(stable = stable) to false
        }
    }
}
