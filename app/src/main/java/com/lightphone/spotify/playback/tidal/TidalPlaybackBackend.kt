package com.lightphone.spotify.playback.tidal

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lightphone.spotify.data.native.NativeMetadataGateway
import com.lightphone.spotify.data.tidal.TidalApiClient
import com.lightphone.spotify.data.tidal.TidalAudioQuality
import com.lightphone.spotify.data.tidal.TidalAuth
import com.lightphone.spotify.ffi.NormalizationType
import com.lightphone.spotify.ffi.QueueSnapshot
import com.lightphone.spotify.ffi.RepeatMode
import com.lightphone.spotify.ffi.StreamingQuality
import com.lightphone.spotify.playback.backend.PlaybackBackend
import com.lightphone.spotify.playback.backend.PlaybackEventListener
import java.io.File
import java.util.concurrent.Executors

/**
 * [PlaybackBackend] for TIDAL, built on Media3 [ExoPlayer].
 *
 * **Resolve off the main thread** (high-tide / mopidy-tidal pattern): hit
 * `playbackinfopostpaywall`, write clear DASH MPD to a temp file (or use BTS
 * progressive HTTPS), then hand ExoPlayer a concrete [MediaItem]. Doing resolve
 * inside [androidx.media3.exoplayer.source.MediaSource.Factory] caused
 * [android.os.NetworkOnMainThreadException] crashes.
 */
@UnstableApi
class TidalPlaybackBackend(
    context: Context,
    private val auth: TidalAuth,
    private val api: TidalApiClient,
) : PlaybackBackend {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var listener: PlaybackEventListener? = null

    @Volatile
    private var tidalQuality: TidalAudioQuality = auth.audioQuality()

    @Volatile
    private var shuffle: Boolean = false

    @Volatile
    private var repeat: RepeatMode = RepeatMode.OFF

    @Volatile
    private var contextLabel: String? = null

    /** Media ids (canonical `tidal:track:{id}`) added via [addToQueue]. */
    private val manualMediaIds = LinkedHashSet<String>()

    @Volatile
    private var cachedQueue: QueueSnapshot =
        QueueSnapshot(null, emptyList(), null, emptyList())

    private val player: ExoPlayer by lazy { buildPlayer() }
    private val mpdCacheDir = File(appContext.cacheDir, "tidal-mpd")
    private val resolveExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tidal-resolve").apply { isDaemon = true }
    }

    /** Exposed so the MediaSession (PlaybackService) can drive TIDAL directly. */
    fun exoPlayer(): ExoPlayer = player

    private fun buildPlayer(): ExoPlayer {
        val cacheFactory = TidalMediaCache.cacheDataSourceFactory(appContext, api)
        return ExoPlayer.Builder(appContext)
            // DefaultMediaSourceFactory picks Progressive vs DASH from mime/uri
            // after we've already resolved off the main thread.
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { it.addListener(playerListener) }
    }

    // --- listener setup -----------------------------------------------------

    override fun setListener(listener: PlaybackEventListener) {
        this.listener = listener
    }

    override val nativeMetadataGateway: NativeMetadataGateway? = null

    // --- session / lifecycle ------------------------------------------------

    override fun isLoggedIn(): Boolean = auth.isAuthorized()
    override fun isSessionConnected(): Boolean = auth.isAuthorized()
    override fun ensurePlaybackReady() {
        // Fill countryCode/userId from /sessions before the first playbackinfo call.
        runCatching { auth.ensureSessionMeta() }
    }
    override fun setAppForeground(foreground: Boolean) { /* ExoPlayer needs no foreground hint */ }
    override fun forceReconnectCheck() { /* no persistent socket to reconnect */ }
    override fun recreateAudioSink() { /* ExoPlayer manages its own sink */ }

    // --- auth ---------------------------------------------------------------

    override fun beginLogin(): String = auth.buildAuthorizeUrl()

    override fun loginWithOauthCode(code: String, state: String?) {
        auth.exchangeCode(code, state).getOrThrow()
    }

    override fun loginWithCachedCredentials(): Boolean = auth.isAuthorized()

    override fun logout() {
        onPlayer {
            player.stop()
            player.clearMediaItems()
        }
        manualMediaIds.clear()
        auth.clearAll()
    }

    // --- transport ----------------------------------------------------------

    override fun playUris(uris: List<String>, startIndex: UInt, contextLabel: String?) {
        this.contextLabel = contextLabel
        manualMediaIds.clear()
        if (uris.isEmpty()) return
        val start = startIndex.toInt().coerceIn(0, uris.lastIndex)
        listener?.onLoading()
        // Resolve on a worker — never call playbackinfo from the main looper
        // (MediaSource.Factory.createMediaSource runs there during setMediaItems).
        resolveExecutor.execute {
            try {
                runCatching { auth.ensureSessionMeta() }
                // Resolve a window around the start index so long playlists don't
                // block forever / burn expired CDN URLs for distant tracks.
                val from = (start - 1).coerceAtLeast(0)
                val to = (start + 2).coerceAtMost(uris.lastIndex)
                val items = uris.mapIndexed { index, uri ->
                    if (index in from..to) {
                        TidalPlayableItems.fromCanonicalUri(api, uri, tidalQuality, mpdCacheDir)
                    } else {
                        // Placeholder; replaced when approached (see onMediaItemTransition).
                        MediaItem.Builder().setMediaId(uri).setUri(Uri.EMPTY).build()
                    }
                }
                mainHandler.post {
                    player.setMediaItems(items, start, 0L)
                    player.prepare()
                    player.playWhenReady = true
                    refreshQueue()
                    ensureResolvedAround(start)
                }
            } catch (e: Exception) {
                android.util.Log.e("TidalPlayback", "playUris resolve failed", e)
                mainHandler.post {
                    listener?.onError(humanizePlaybackError(e.message ?: "Couldn't open stream"))
                }
            }
        }
    }

    override fun pause() = onPlayer { player.playWhenReady = false }
    override fun resume() = onPlayer { player.playWhenReady = true }
    override fun next() = onPlayer {
        val next = player.currentMediaItemIndex + 1
        if (next >= player.mediaItemCount) return@onPlayer
        seekToResolvedIndex(next)
    }
    override fun previous() = onPlayer {
        val prev = player.currentMediaItemIndex - 1
        if (prev < 0) {
            player.seekTo(0L)
            return@onPlayer
        }
        seekToResolvedIndex(prev)
    }
    override fun seek(positionMs: UInt) = onPlayer { player.seekTo(positionMs.toLong()) }

    // --- queue --------------------------------------------------------------

    override fun getQueue(): QueueSnapshot = cachedQueue

    override fun addToQueue(uri: String) {
        manualMediaIds.add(uri)
        resolveExecutor.execute {
            try {
                val item = TidalPlayableItems.fromCanonicalUri(api, uri, tidalQuality, mpdCacheDir)
                mainHandler.post {
                    val insertAt = insertionIndexForManual()
                    player.addMediaItem(insertAt, item)
                    refreshQueue()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    listener?.onError(humanizePlaybackError(e.message ?: "Couldn't queue track"))
                }
            }
        }
    }

    override fun clearManualQueue() {
        onPlayer {
            var i = player.mediaItemCount - 1
            while (i > player.currentMediaItemIndex) {
                if (player.getMediaItemAt(i).mediaId in manualMediaIds) player.removeMediaItem(i)
                i--
            }
            manualMediaIds.clear()
            refreshQueue()
        }
    }

    override fun moveQueueItemUp(index: UInt) = moveInSublist(index.toInt(), manual = true, up = true)
    override fun moveQueueItemDown(index: UInt) = moveInSublist(index.toInt(), manual = true, up = false)
    override fun moveContextItemUp(index: UInt) = moveInSublist(index.toInt(), manual = false, up = true)
    override fun moveContextItemDown(index: UInt) = moveInSublist(index.toInt(), manual = false, up = false)

    // --- modes --------------------------------------------------------------

    override fun getShuffle(): Boolean = shuffle
    override fun toggleShuffle(): Boolean {
        shuffle = !shuffle
        onPlayer { player.shuffleModeEnabled = shuffle }
        return shuffle
    }

    override fun getRepeatMode(): RepeatMode = repeat
    override fun toggleRepeat(): RepeatMode {
        repeat = when (repeat) {
            RepeatMode.OFF -> RepeatMode.CONTEXT
            RepeatMode.CONTEXT -> RepeatMode.TRACK
            RepeatMode.TRACK -> RepeatMode.OFF
        }
        onPlayer { player.repeatMode = repeat.toExoRepeat() }
        return repeat
    }

    // --- settings -----------------------------------------------------------

    /** Spotify-shaped API (unused for TIDAL URIs); kept for [PlaybackBackend] parity. */
    override fun getStreamingQuality(): StreamingQuality = when (tidalQuality) {
        TidalAudioQuality.EXTRA_LOW -> StreamingQuality.LOW
        TidalAudioQuality.LOW -> StreamingQuality.HIGH
        TidalAudioQuality.HIGH, TidalAudioQuality.MAX -> StreamingQuality.HIGH
    }

    override fun setStreamingQuality(quality: StreamingQuality) {
        // Map legacy Spotify settings onto the nearest TIDAL tier if something
        // still calls this path; prefer [setTidalAudioQuality].
        val mapped = when (quality) {
            StreamingQuality.LOW -> TidalAudioQuality.EXTRA_LOW
            StreamingQuality.NORMAL -> TidalAudioQuality.LOW
            StreamingQuality.HIGH -> TidalAudioQuality.HIGH
        }
        setTidalAudioQuality(mapped)
    }

    fun getTidalAudioQuality(): TidalAudioQuality = tidalQuality

    fun setTidalAudioQuality(quality: TidalAudioQuality) {
        tidalQuality = quality
        auth.setAudioQuality(quality)
    }
    override fun getGaplessEnabled(): Boolean = true
    override fun setGaplessEnabled(enabled: Boolean) { /* ExoPlayer playlist is inherently gapless */ }
    override fun getNormalizationEnabled(): Boolean = false
    override fun setNormalizationEnabled(enabled: Boolean) { /* TIDAL loudness normalization not wired */ }
    override fun getNormalizationType(): NormalizationType = NormalizationType.AUTO
    override fun setNormalizationType(type: NormalizationType) { /* no-op */ }
    override fun getProxy(): String? = null
    override fun setProxy(proxy: String?) { /* no-op */ }

    // --- cache / prefetch ---------------------------------------------------

    override fun clearAudioCache() {
        runCatching {
            val cache = TidalMediaCache.cache(appContext)
            for (key in cache.keys.toList()) {
                cache.removeResource(key)
            }
        }
    }

    override fun bufferCurrentToEnd() { /* ExoPlayer buffers ahead automatically */ }
    override fun prefetchUpcoming(ahead: UInt) { /* handled by ExoPlayer's default LoadControl */ }

    // --- internals ----------------------------------------------------------

    private fun needsResolve(item: MediaItem): Boolean {
        val uri = item.localConfiguration?.uri ?: return true
        return uri == Uri.EMPTY || uri.scheme == TidalMediaCache.STREAM_SCHEME
    }

    /** Snapshot placeholders on the main thread, resolve on the worker, replace on main. */
    private fun ensureResolvedAround(index: Int) {
        val count = player.mediaItemCount
        if (count == 0) return
        val pending = buildList {
            for (i in listOf(index, index + 1, index + 2)) {
                if (i !in 0 until count) continue
                val item = player.getMediaItemAt(i)
                if (needsResolve(item) && item.mediaId.startsWith("tidal:")) {
                    add(i to item.mediaId)
                }
            }
        }
        if (pending.isEmpty()) return
        resolveExecutor.execute {
            for ((i, canonical) in pending) {
                try {
                    val playable = TidalPlayableItems.fromCanonicalUri(
                        api, canonical, tidalQuality, mpdCacheDir,
                    )
                    mainHandler.post {
                        if (i < player.mediaItemCount &&
                            player.getMediaItemAt(i).mediaId == canonical &&
                            needsResolve(player.getMediaItemAt(i))
                        ) {
                            player.replaceMediaItem(i, playable)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("TidalPlayback", "JIT resolve failed for $canonical", e)
                }
            }
        }
    }

    /** Resolve a placeholder before seeking to it so ExoPlayer never opens Uri.EMPTY. */
    private fun seekToResolvedIndex(index: Int) {
        val item = player.getMediaItemAt(index)
        if (!needsResolve(item)) {
            player.seekTo(index, 0L)
            ensureResolvedAround(index)
            return
        }
        val canonical = item.mediaId
        listener?.onLoading()
        resolveExecutor.execute {
            try {
                val playable = TidalPlayableItems.fromCanonicalUri(
                    api, canonical, tidalQuality, mpdCacheDir,
                )
                mainHandler.post {
                    if (index < player.mediaItemCount &&
                        player.getMediaItemAt(index).mediaId == canonical
                    ) {
                        player.replaceMediaItem(index, playable)
                        player.seekTo(index, 0L)
                        player.playWhenReady = true
                        ensureResolvedAround(index)
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    listener?.onError(humanizePlaybackError(e.message ?: "Couldn't open stream"))
                }
            }
        }
    }

    /** First index after the current item that is NOT already a manual-queue item. */
    private fun insertionIndexForManual(): Int {
        var i = player.currentMediaItemIndex + 1
        while (i < player.mediaItemCount && player.getMediaItemAt(i).mediaId in manualMediaIds) i++
        return i.coerceAtMost(player.mediaItemCount)
    }

    private fun moveInSublist(index: Int, manual: Boolean, up: Boolean) = onPlayer {
        val slots = upcomingSlots(manual)
        if (index < 0 || index >= slots.size) return@onPlayer
        val from = slots[index]
        val to = if (up) from - 1 else from + 1
        if (to <= player.currentMediaItemIndex || to >= player.mediaItemCount) return@onPlayer
        player.moveMediaItem(from, to)
        refreshQueue()
    }

    /** Player indices of upcoming items belonging to the manual (or context) sublist. */
    private fun upcomingSlots(manual: Boolean): List<Int> {
        val result = mutableListOf<Int>()
        for (i in (player.currentMediaItemIndex + 1) until player.mediaItemCount) {
            val isManual = player.getMediaItemAt(i).mediaId in manualMediaIds
            if (isManual == manual) result.add(i)
        }
        return result
    }

    private fun refreshQueue() {
        val count = player.mediaItemCount
        val current = player.currentMediaItemIndex
        val nowPlaying = player.currentMediaItem?.mediaId
        val nextInQueue = mutableListOf<String>()
        val nextFromContext = mutableListOf<String>()
        if (current in 0 until count) {
            for (i in (current + 1) until count) {
                val id = player.getMediaItemAt(i).mediaId
                if (id in manualMediaIds) nextInQueue.add(id) else nextFromContext.add(id)
            }
        }
        cachedQueue = QueueSnapshot(nowPlaying, nextInQueue, contextLabel, nextFromContext)
        listener?.onQueueChanged()
    }

    /** Run [block] on the player's (main) thread. */
    private fun onPlayer(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private val positionPoller = object : Runnable {
        override fun run() {
            if (player.isPlaying) {
                listener?.onPositionChanged(player.currentPosition)
                mainHandler.postDelayed(this, POSITION_POLL_MS)
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.mediaId?.let { listener?.onTrackChanged(it) }
            refreshQueue()
            ensureResolvedAround(player.currentMediaItemIndex)
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> {
                    listener?.onLoading()
                    listener?.onBuffering(true)
                }
                Player.STATE_READY -> listener?.onBuffering(false)
                Player.STATE_ENDED -> listener?.onEndOfTrack()
                Player.STATE_IDLE -> {}
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                listener?.onPlaying(player.currentPosition)
                mainHandler.post(positionPoller)
            } else {
                listener?.onPaused(player.currentPosition)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            player.currentMediaItem?.mediaId?.let { listener?.onUnavailable(it) }
            // Walk the cause chain — Media3 wraps TIDAL/IO failures as "Source error".
            val chain = generateSequence(error as Throwable) { it.cause }.toList()
            android.util.Log.e(
                "TidalPlayback",
                "player error ${error.errorCodeName}: " +
                    chain.joinToString(" <- ") { it.javaClass.simpleName + ": " + (it.message ?: "") },
            )
            val detail = chain.mapNotNull { it.message }
                .firstOrNull { msg ->
                    !msg.equals("Source error", ignoreCase = true) && (
                        msg.contains("userMessage") ||
                            msg.contains("HTTP ") ||
                            msg.contains("Cleartext", ignoreCase = true) ||
                            msg.contains("Widevine", ignoreCase = true) ||
                            msg.contains("dash", ignoreCase = true) ||
                            msg.contains("bts", ignoreCase = true) ||
                            msg.contains("403") ||
                            msg.contains("encrypted", ignoreCase = true) ||
                            msg.contains("unauthorized", ignoreCase = true) ||
                            msg.contains("cancelled", ignoreCase = true) ||
                            msg.contains("SSL", ignoreCase = true) ||
                            msg.contains("response code", ignoreCase = true)
                        )
                }
                ?: chain.mapNotNull { it.message }
                    .firstOrNull { !it.equals("Source error", ignoreCase = true) }
                ?: error.message
                ?: error.errorCodeName
            listener?.onError(humanizePlaybackError(detail))
        }
    }

    private fun RepeatMode.toExoRepeat(): Int = when (this) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.CONTEXT -> Player.REPEAT_MODE_ALL
        RepeatMode.TRACK -> Player.REPEAT_MODE_ONE
    }

    companion object {
        private const val POSITION_POLL_MS = 500L

        private fun humanizePlaybackError(raw: String): String = when {
            raw.contains("4032") || raw.contains("subscription location", ignoreCase = true) ->
                "Track unavailable here (region/subscription)."
            raw.contains("unauthorized", ignoreCase = true) ||
                raw.contains("sign in again", ignoreCase = true) ->
                "TIDAL session expired — sign out and sign in again."
            raw.contains("Cleartext", ignoreCase = true) ->
                "Stream blocked (cleartext HTTP)."
            raw.contains("Widevine", ignoreCase = true) ->
                "This track needs Widevine DRM."
            raw.contains("encrypted", ignoreCase = true) ->
                "This track is DRM-encrypted."
            raw.contains("cancelled", ignoreCase = true) ->
                "Playback interrupted — try again."
            raw.equals("Source error", ignoreCase = true) ->
                "Couldn't open stream. Try another quality or re-login."
            else -> raw.take(160)
        }
    }
}
