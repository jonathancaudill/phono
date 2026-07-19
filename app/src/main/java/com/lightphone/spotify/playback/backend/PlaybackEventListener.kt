package com.lightphone.spotify.playback.backend

/**
 * Backend-neutral playback event contract. Mirrors the 12 callbacks of the
 * UniFFI `PlayerEventListener` so that [PlaybackController] can consume events
 * from either the librespot (Spotify) engine or the Media3-based TIDAL engine
 * without depending on the generated FFI type directly.
 *
 * The [com.lightphone.spotify.playback.backend.LibrespotPlaybackBackend] adapts
 * the FFI listener to this interface; the TIDAL backend drives it from an
 * ExoPlayer `Player.Listener`.
 */
interface PlaybackEventListener {
    fun onTrackChanged(uri: String)
    fun onLoading()
    fun onPlaying(positionMs: Long)
    fun onPaused(positionMs: Long)
    fun onPositionChanged(positionMs: Long)
    fun onEndOfTrack()
    fun onUnavailable(uri: String)
    fun onConnectionLost()
    fun onConnectionRestored()
    fun onError(message: String)
    fun onQueueChanged()
    fun onBuffering(stalled: Boolean)
}
