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
 * UniFFI buffer/prefetch calls are no-ops until librespot-playback patch ships (P2).
 */
class StreamingPolicy(
    private val controller: PlaybackController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val context: Context get() = controller.appContextInternal

    @Volatile
    private var tier: NetworkTier = NetworkTier.FAIR

    fun onCapabilitiesChanged(caps: NetworkCapabilities) {
        tier = classify(caps)
        if (tier != NetworkTier.OFFLINE && tier != NetworkTier.POOR && controller.state.value.isPlaying) {
            maybeBufferOpportunistically()
        }
    }

    fun onOffline() {
        tier = NetworkTier.OFFLINE
    }

    fun onTrackActive() {
        if (!controller.state.value.isPlaying) return
        maybeBufferOpportunistically()
    }

    fun onPlaybackStall() {
        if (isBatteryConstrained()) return
        bankCurrentTrack()
    }

    fun currentTier(): NetworkTier = tier

    fun prefetchDepth(): Int = when (tier) {
        NetworkTier.GOOD_UNMETERED -> 3
        NetworkTier.GOOD_METERED -> 1
        else -> 0
    }

    private fun maybeBufferOpportunistically() {
        if (isBatteryConstrained()) return
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
            downKbps >= 1500 -> NetworkTier.GOOD_METERED
            downKbps >= 500 -> NetworkTier.FAIR
            else -> NetworkTier.POOR
        }
    }
}
