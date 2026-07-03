package com.lightphone.spotify.playback

import android.content.Context
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    fun onCapabilitiesChanged(caps: NetworkCapabilities) {
        val raw = classify(caps)
        when {
            raw.ordinal > stableTier.ordinal -> {
                tierDownCount = 0
                tierUpCount++
                if (tierUpCount >= TIER_UP_SAMPLES) {
                    stableTier = raw
                    tierUpCount = 0
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
        if (raw != NetworkTier.OFFLINE && controller.state.value.isPlaying) {
            maybeBufferOpportunistically()
        }
    }

    fun onOffline() {
        stableTier = NetworkTier.OFFLINE
        tierUpCount = 0
        tierDownCount = 0
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

    fun prefetchDepth(): Int = when (stableTier) {
        NetworkTier.GOOD_UNMETERED -> 3
        NetworkTier.GOOD_METERED, NetworkTier.FAIR -> 1
        else -> 0
    }

    private fun maybeBufferOpportunistically() {
        if (isBatteryConstrained()) return
        if (stableTier == NetworkTier.OFFLINE) return
        scope.launch {
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

    private fun classify(caps: NetworkCapabilities): NetworkTier {
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return NetworkTier.OFFLINE
        }
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            return NetworkTier.POOR
        }
        val downKbps = caps.linkDownstreamBandwidthKbps
        val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return when {
            unmetered && (
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                ) -> NetworkTier.GOOD_UNMETERED
            downKbps >= 1200 -> NetworkTier.GOOD_METERED
            downKbps >= 400 -> NetworkTier.FAIR
            else -> NetworkTier.POOR
        }
    }

    companion object {
        private const val TIER_UP_SAMPLES = 3
        private const val TIER_DOWN_SAMPLES = 2
    }
}
