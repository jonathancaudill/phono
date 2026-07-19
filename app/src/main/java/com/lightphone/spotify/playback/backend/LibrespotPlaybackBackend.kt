package com.lightphone.spotify.playback.backend

import com.lightphone.spotify.data.native.NativeMetadataGateway
import com.lightphone.spotify.data.native.NativeSessionRequiredException
import com.lightphone.spotify.ffi.LibrespotEngine
import com.lightphone.spotify.ffi.NormalizationType
import com.lightphone.spotify.ffi.PlayerEventListener
import com.lightphone.spotify.ffi.QueueSnapshot
import com.lightphone.spotify.ffi.RepeatMode
import com.lightphone.spotify.ffi.StreamingQuality

/**
 * [PlaybackBackend] backed by the Rust librespot [LibrespotEngine] (the Spotify
 * stack). Delegates 1:1 so behavior is byte-identical to the pre-abstraction
 * controller, and adapts the FFI [PlayerEventListener] to the neutral
 * [PlaybackEventListener].
 *
 * It also owns the Spotify [NativeMetadataGateway] (Login5 spclient bridge) that
 * used to be constructed inline in `PlaybackController.attachEngine`.
 */
class LibrespotPlaybackBackend(
    private val engine: LibrespotEngine,
) : PlaybackBackend, PlayerEventListener {

    @Volatile
    private var listener: PlaybackEventListener? = null

    override fun setListener(listener: PlaybackEventListener) {
        this.listener = listener
        engine.setListener(this)
    }

    override val nativeMetadataGateway: NativeMetadataGateway = object : NativeMetadataGateway {
        override fun requireLoggedIn() {
            if (!engine.isLoggedIn()) throw NativeSessionRequiredException()
        }

        override fun isLoggedIn(): Boolean = engine.isLoggedIn()

        override fun sessionUsername(): String = engine.nativeSessionUsername()

        override fun playlistDetail(playlistId: String, trackLimit: Int) =
            engine.playlistDetailNative(playlistId, trackLimit.toUInt())

        override fun playlistRootlist(from: Int, length: Int) =
            engine.playlistRootlist(from.toUInt(), length.toUInt())

        override fun artistDetail(artistId: String, albumLimit: Int, topTrackLimit: Int) =
            engine.artistDetailNative(artistId, albumLimit.toUInt(), topTrackLimit.toUInt())

        override fun userDisplayName(username: String): String? =
            engine.userDisplayNameNative(username)

        override fun createPlaylist(name: String, isPublic: Boolean) =
            engine.createPlaylistNative(name, isPublic)

        override fun updatePlaylistMetadata(
            playlistId: String,
            revisionB64: String,
            name: String?,
            isPublic: Boolean?,
        ) = engine.updatePlaylistMetadataNative(playlistId, revisionB64, name, isPublic)

        override fun addPlaylistTracks(
            playlistId: String,
            revisionB64: String,
            uris: List<String>,
            position: Int?,
        ) = engine.playlistAddTracksNative(playlistId, revisionB64, uris, position?.toUInt())

        override fun removePlaylistTracks(
            playlistId: String,
            revisionB64: String,
            uris: List<String>,
        ) = engine.playlistRemoveTracksNative(playlistId, revisionB64, uris)

        override fun reorderPlaylistTracks(
            playlistId: String,
            revisionB64: String,
            rangeStart: Int,
            insertBefore: Int,
            rangeLength: Int,
        ) = engine.playlistReorderNative(
            playlistId,
            revisionB64,
            rangeStart.toUInt(),
            insertBefore.toUInt(),
            rangeLength.toUInt(),
        )

        override fun followPlaylist(playlistUri: String) =
            engine.rootlistAddNative(playlistUri)

        override fun unfollowPlaylist(playlistUri: String) =
            engine.rootlistRemoveNative(playlistUri)

        override fun addToRootlist(playlistUri: String) =
            engine.rootlistAddNative(playlistUri)
    }

    // --- session / lifecycle ------------------------------------------------
    override fun isLoggedIn(): Boolean = engine.isLoggedIn()
    override fun isSessionConnected(): Boolean = engine.isSessionConnected()
    override fun ensurePlaybackReady() { engine.ensurePlaybackReady() }
    override fun setAppForeground(foreground: Boolean) = engine.setAppForeground(foreground)
    override fun forceReconnectCheck() = engine.forceReconnectCheck()
    override fun recreateAudioSink() = engine.recreateAudioSink()

    // --- auth ---------------------------------------------------------------
    override fun beginLogin(): String = engine.beginLogin()
    override fun loginWithOauthCode(code: String, state: String?) =
        engine.loginWithOauthCode(code, state)
    override fun loginWithCachedCredentials(): Boolean = engine.loginWithCachedCredentials()
    override fun logout() = engine.logout()

    // --- transport ----------------------------------------------------------
    override fun playUris(uris: List<String>, startIndex: UInt, contextLabel: String?) =
        engine.playUris(uris, startIndex, contextLabel)
    override fun pause() = engine.pause()
    override fun resume() = engine.resume()
    override fun next() = engine.next()
    override fun previous() = engine.previous()
    override fun seek(positionMs: UInt) = engine.seek(positionMs)

    // --- queue --------------------------------------------------------------
    override fun getQueue(): QueueSnapshot = engine.getQueue()
    override fun addToQueue(uri: String) = engine.addToQueue(uri)
    override fun clearManualQueue() = engine.clearManualQueue()
    override fun moveQueueItemUp(index: UInt) = engine.moveQueueItemUp(index)
    override fun moveQueueItemDown(index: UInt) = engine.moveQueueItemDown(index)
    override fun moveContextItemUp(index: UInt) = engine.moveContextItemUp(index)
    override fun moveContextItemDown(index: UInt) = engine.moveContextItemDown(index)

    // --- modes --------------------------------------------------------------
    override fun getShuffle(): Boolean = engine.getShuffle()
    override fun toggleShuffle(): Boolean = engine.toggleShuffle()
    override fun getRepeatMode(): RepeatMode = engine.getRepeatMode()
    override fun toggleRepeat(): RepeatMode = engine.toggleRepeat()

    // --- settings -----------------------------------------------------------
    override fun getStreamingQuality(): StreamingQuality = engine.getStreamingQuality()
    override fun setStreamingQuality(quality: StreamingQuality) = engine.setStreamingQuality(quality)
    override fun getGaplessEnabled(): Boolean = engine.getGaplessEnabled()
    override fun setGaplessEnabled(enabled: Boolean) = engine.setGaplessEnabled(enabled)
    override fun getNormalizationEnabled(): Boolean = engine.getNormalizationEnabled()
    override fun setNormalizationEnabled(enabled: Boolean) = engine.setNormalizationEnabled(enabled)
    override fun getNormalizationType(): NormalizationType = engine.getNormalizationType()
    override fun setNormalizationType(type: NormalizationType) = engine.setNormalizationType(type)
    override fun getProxy(): String? = engine.getProxy()
    override fun setProxy(proxy: String?) = engine.setProxy(proxy)

    // --- cache / prefetch ---------------------------------------------------
    override fun clearAudioCache() = engine.clearAudioCache()
    override fun bufferCurrentToEnd() = engine.bufferCurrentToEnd()
    override fun prefetchUpcoming(ahead: UInt) = engine.prefetchUpcoming(ahead)

    // --- FFI PlayerEventListener -> neutral PlaybackEventListener ------------
    override fun onTrackChanged(uri: String) { listener?.onTrackChanged(uri) }
    override fun onLoading() { listener?.onLoading() }
    override fun onPlaying(positionMs: Long) { listener?.onPlaying(positionMs) }
    override fun onPaused(positionMs: Long) { listener?.onPaused(positionMs) }
    override fun onPositionChanged(positionMs: Long) { listener?.onPositionChanged(positionMs) }
    override fun onEndOfTrack() { listener?.onEndOfTrack() }
    override fun onUnavailable(uri: String) { listener?.onUnavailable(uri) }
    override fun onConnectionLost() { listener?.onConnectionLost() }
    override fun onConnectionRestored() { listener?.onConnectionRestored() }
    override fun onError(message: String) { listener?.onError(message) }
    override fun onQueueChanged() { listener?.onQueueChanged() }
    override fun onBuffering(stalled: Boolean) { listener?.onBuffering(stalled) }
}
