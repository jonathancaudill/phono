package com.lightphone.spotify.playback.backend

import com.lightphone.spotify.data.native.NativeMetadataGateway
import com.lightphone.spotify.ffi.NormalizationType
import com.lightphone.spotify.ffi.QueueSnapshot
import com.lightphone.spotify.ffi.RepeatMode
import com.lightphone.spotify.ffi.StreamingQuality

/**
 * Backend-neutral playback engine surface. This is the subset of the librespot
 * [com.lightphone.spotify.ffi.LibrespotEngine] that [com.lightphone.spotify.playback.PlaybackController]
 * actually calls. Two implementations exist:
 *
 *  - [LibrespotPlaybackBackend] — wraps the Rust engine (Spotify).
 *  - [com.lightphone.spotify.playback.tidal.TidalPlaybackBackend] — ExoPlayer + TIDAL.
 *
 * The interface intentionally reuses the generated FFI value types
 * ([QueueSnapshot], [RepeatMode], [StreamingQuality], [NormalizationType]) so the
 * Spotify path is a 1:1 passthrough and the controller stays unchanged. These are
 * plain data classes / enums with public constructors, so the TIDAL backend can
 * construct and return them too.
 */
interface PlaybackBackend {
    /** Register the neutral event listener before any transport call. */
    fun setListener(listener: PlaybackEventListener)

    /**
     * Spotify-only Login5 spclient metadata bridge. Null for backends (TIDAL) that
     * fetch all metadata over HTTP and need no native gateway.
     */
    val nativeMetadataGateway: NativeMetadataGateway?

    // --- session / lifecycle ------------------------------------------------
    fun isLoggedIn(): Boolean
    fun isSessionConnected(): Boolean
    /** Bring the session up (idempotent). Throws on failure. */
    fun ensurePlaybackReady()
    fun setAppForeground(foreground: Boolean)
    /** Tell the backend whether the device currently has internet. */
    fun setNetworkOnline(online: Boolean) {}
    fun forceReconnectCheck()
    fun recreateAudioSink()

    // --- auth ---------------------------------------------------------------
    /** Begin an interactive login, returning an authorize URL (Spotify) or handled internally (TIDAL). */
    fun beginLogin(): String
    fun loginWithOauthCode(code: String, state: String?)
    fun loginWithCachedCredentials(): Boolean
    fun logout()

    // --- transport ----------------------------------------------------------
    fun playUris(uris: List<String>, startIndex: UInt, contextLabel: String?)
    fun pause()
    fun resume()
    fun next()
    fun previous()
    fun seek(positionMs: UInt)

    // --- queue --------------------------------------------------------------
    fun getQueue(): QueueSnapshot
    fun addToQueue(uri: String)
    fun clearManualQueue()
    fun moveQueueItemUp(index: UInt)
    fun moveQueueItemDown(index: UInt)
    fun moveContextItemUp(index: UInt)
    fun moveContextItemDown(index: UInt)

    // --- modes --------------------------------------------------------------
    fun getShuffle(): Boolean
    fun toggleShuffle(): Boolean
    fun getRepeatMode(): RepeatMode
    fun toggleRepeat(): RepeatMode

    // --- settings -----------------------------------------------------------
    fun getStreamingQuality(): StreamingQuality
    fun setStreamingQuality(quality: StreamingQuality)
    fun getDownloadQuality(): StreamingQuality = StreamingQuality.HIGH
    fun setDownloadQuality(quality: StreamingQuality) {}
    fun getGaplessEnabled(): Boolean
    fun setGaplessEnabled(enabled: Boolean)
    fun getNormalizationEnabled(): Boolean
    fun setNormalizationEnabled(enabled: Boolean)
    fun getNormalizationType(): NormalizationType
    fun setNormalizationType(type: NormalizationType)
    fun getProxy(): String?
    fun setProxy(proxy: String?)

    // --- cache / prefetch ---------------------------------------------------
    fun clearAudioCache()
    fun bufferCurrentToEnd()
    fun prefetchUpcoming(ahead: UInt)
}
