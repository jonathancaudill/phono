package com.lightphone.spotify.playback

/**
 * Pure decisions for Spotify playback continuity across network/session events.
 *
 * Dropping the librespot [Active] (what [PlaybackBackend.forceReconnectCheck]
 * does) always emits a real Pause then Play: the player is destroyed and a new
 * one is loaded. Optimistic banking ([PlaybackBackend.bufferCurrentToEnd]) only
 * helps if we **keep** that player alive. A fully-banked current track should
 * ride through a transport blip; a dead session still has to rebuild.
 */
object ReconnectPolicy {
    const val NETWORK_HANDOFF_GRACE_MS = 3_000L
    const val RECONNECT_DEBOUNCE_MS = 6_000L
    const val FORCE_RECONNECT_COOLDOWN_MS = 5_000L
    const val TRANSPORT_CONFIRM_SAMPLES = 2
    const val STALL_BUFFERING_MS = 8_000L
    const val STALL_POLL_MS = 2_000L
    const val TRANSPORT_COALESCE_MS = 300L
    const val AUDIO_ROUTE_DEBOUNCE_MS = 400L

    fun shouldDeferMonitor(playing: Boolean, appForeground: Boolean): Boolean =
        !playing && !appForeground

    fun shouldCoalesceTransport(reconnecting: Boolean): Boolean = reconnecting

    fun shouldForceReconnectOnAvailable(
        connected: Boolean,
        reconnecting: Boolean,
        sessionDead: Boolean,
    ): Boolean = !connected || reconnecting || sessionDead

    /**
     * Confirmed wifi↔cellular (or ethernet) handoff while playing. Teardown is
     * an audible pause/play, so skip it when the current track is already in
     * the audio cache — the live player can finish from disk even if the AP
     * socket is about to die.
     *
     * If we are already reconnecting, keep rebuilding (the player is gone).
     */
    fun shouldTearDownOnTransportHandoff(
        playing: Boolean,
        reconnecting: Boolean,
        fullyBuffered: Boolean,
    ): Boolean {
        if (reconnecting) return true
        if (!playing) return false
        return !fullyBuffered
    }

    /**
     * Native [PlaybackBackend.forceReconnectCheck] tears down Active. Same
     * rule as the Kotlin handoff gate so a stray call cannot blip a banked
     * track.
     */
    fun shouldTearDownOnForceReconnect(
        playing: Boolean,
        fullyBuffered: Boolean,
    ): Boolean = !(playing && fullyBuffered)

    /** Stall watchdog banks; it must never force-reconnect (librespot can exit). */
    fun shouldForceReconnectOnStall(): Boolean = false

    /** Path C AudioTrack sink owns routing; pause/resume is a rodio leftover. */
    fun shouldPauseOnAudioRouteChange(useAudioTrackSink: Boolean): Boolean =
        !useAudioTrackSink
}

/**
 * Two consecutive samples of a *new* validated transport while playing confirm
 * a handoff. Same-transport flaps reset the counter. A single blip is ignored.
 */
class TransportHandoffTracker(
    private val confirmSamples: Int = ReconnectPolicy.TRANSPORT_CONFIRM_SAMPLES,
) {
    var lastTransport: Int? = null
        private set
    var pendingTransport: Int? = null
        private set
    var confirmCount: Int = 0
        private set

    fun resetPending() {
        pendingTransport = null
        confirmCount = 0
    }

    fun commitTransport(transport: Int) {
        lastTransport = transport
        resetPending()
    }

    /**
     * @return true when a validated transport change has been confirmed and
     * the caller should consider a session reconnect.
     */
    fun onCapabilities(
        transport: Int?,
        validated: Boolean,
        playingOrReconnecting: Boolean,
    ): Boolean {
        if (transport != null && lastTransport != null && transport != lastTransport) {
            if (pendingTransport == transport) {
                confirmCount++
            } else {
                pendingTransport = transport
                confirmCount = 1
            }
        } else if (transport == lastTransport) {
            resetPending()
        } else if (transport != null && lastTransport == null) {
            lastTransport = transport
        }
        // Do not promote lastTransport on the first sample of a *new* path —
        // that would make confirmSamples unreachable (the next sample would
        // look like "same transport" and reset the counter).
        if (
            validated &&
            confirmCount >= confirmSamples &&
            playingOrReconnecting
        ) {
            lastTransport = transport ?: lastTransport
            resetPending()
            return true
        }
        return false
    }
}

/**
 * Debounce + native cooldown: Kotlin waits [ReconnectPolicy.RECONNECT_DEBOUNCE_MS]
 * then Rust refuses another teardown for [ReconnectPolicy.FORCE_RECONNECT_COOLDOWN_MS].
 */
class ReconnectCooldown(
    private val debounceMs: Long = ReconnectPolicy.RECONNECT_DEBOUNCE_MS,
    private val nativeCooldownMs: Long = ReconnectPolicy.FORCE_RECONNECT_COOLDOWN_MS,
) {
    private var lastScheduledAtMs: Long? = null
    private var lastNativeAtMs: Long? = null

    fun schedule(nowMs: Long): Boolean {
        lastScheduledAtMs = nowMs
        return true
    }

    /** A newer schedule within the debounce window replaces the pending one. */
    fun fireDue(nowMs: Long): Boolean {
        val scheduled = lastScheduledAtMs ?: return false
        if (nowMs - scheduled < debounceMs) return false
        lastScheduledAtMs = null
        return tryNative(nowMs)
    }

    fun tryNative(nowMs: Long): Boolean {
        val last = lastNativeAtMs
        if (last != null && nowMs - last < nativeCooldownMs) return false
        lastNativeAtMs = nowMs
        return true
    }
}
