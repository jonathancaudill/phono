package com.lightphone.spotify.playback.tidal

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.PriorityTaskManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
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
import com.lightphone.spotify.playback.backend.QueueMoveIndex
import com.lightphone.spotify.playback.backend.QueueMoveOp
import com.lightphone.spotify.playback.backend.QueueMoveSection
import com.lightphone.spotify.playback.media3.CdnRefreshAttempts
import com.lightphone.spotify.playback.media3.CdnUrlRefresher
import com.lightphone.spotify.playback.media3.QualityCeilings
import com.lightphone.spotify.playback.media3.QualityPolicy
import com.lightphone.spotify.playback.NetworkTier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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

    private val qualityPolicy = QualityPolicy(
        initial = auth.audioQuality(),
        ceilingForTier = { tier, requested ->
            QualityCeilings.cap(tier, requested, TIDAL_QUALITY_LADDER)
        },
    )

    /** Network tier hint from [StreamingPolicy] via controller; defaults FAIR. */
    private val networkTier = AtomicReference(NetworkTier.FAIR)

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

    /** Skip listener fan-out while [applyQueueMoves] applies a burst on the player thread. */
    private var deferQueueNotify = false

    private val priorityTaskManager = PriorityTaskManager()
    private val streamBanker = TidalStreamBanker(appContext, priorityTaskManager)
    private val cdnRefreshAttempts = CdnRefreshAttempts()
    private val player: ExoPlayer by lazy { buildPlayer() }
    private val mpdCacheDir = TidalMediaCache.mpdDir(appContext)
    private val resolveExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tidal-resolve").apply { isDaemon = true }
    }
    private val playReporter = TidalPlaybackReporter(auth)

    /** Last look-ahead depth requested by [prefetchUpcoming] (0–3). */
    private val resolveAhead = AtomicInteger(2)

    private fun resolveQuality(): TidalAudioQuality =
        qualityPolicy.effectiveForResolve(networkTier.get())

    /** Called from [com.lightphone.spotify.playback.PlaybackController] when tier changes. */
    override fun setNetworkTierHint(tier: NetworkTier) {
        networkTier.set(tier)
    }

    /** Exposed so the MediaSession (PlaybackService) can drive TIDAL directly. */
    fun exoPlayer(): ExoPlayer = player

    private fun buildPlayer(): ExoPlayer {
        val cacheFactory = TidalMediaCache.cacheDataSourceFactory(
            appContext,
            priorityTaskManager,
            C.PRIORITY_PLAYBACK,
        )
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMsForStreaming(
                /* minBufferMs= */ 15_000,
                /* maxBufferMs= */ 90_000,
                /* bufferForPlaybackMs= */ 2_500,
                /* bufferForPlaybackAfterRebufferMs= */ 5_000,
            )
            .build()
        return ExoPlayer.Builder(appContext)
            // DefaultMediaSourceFactory picks Progressive vs DASH from mime/uri
            // after we've already resolved off the main thread.
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .setLoadControl(loadControl)
            .setPriorityTaskManager(priorityTaskManager)
            .setPriority(C.PRIORITY_PLAYBACK)
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
        playReporter.onTrackStopped()
        streamBanker.shutdown()
        runCatching {
            onPlayer {
                player.stop()
                player.clearMediaItems()
                player.release()
            }
        }
        resolveExecutor.shutdownNow()
        manualMediaIds.clear()
        cdnRefreshAttempts.clearAll()
        auth.clearAll()
    }

    fun setReportPlaysEnabled(enabled: Boolean) = playReporter.setEnabled(enabled)

    fun reportPlaysEnabled(): Boolean = playReporter.isEnabled()

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
                // Resolve a window around the start index so long playlists don't
                // block forever / burn expired CDN URLs for distant tracks.
                val ahead = resolveAhead.get().coerceIn(0, 3)
                val range = TidalPrefetchWindows.playStartResolveRange(start, uris.lastIndex, ahead)
                val offlineByIndex = HashMap<Int, MediaItem>(range.count())
                var needsNetwork = false
                for (index in range) {
                    val offline = TidalPlayableItems.tryOfflineMediaItem(appContext, uris[index])
                    if (offline != null) {
                        offlineByIndex[index] = offline
                    } else {
                        needsNetwork = true
                    }
                }
                // Skip session meta when the start window is fully pinned.
                if (needsNetwork) {
                    runCatching { auth.ensureSessionMeta() }
                }
                val items = uris.mapIndexed { index, uri ->
                    when {
                        index in offlineByIndex -> offlineByIndex.getValue(index)
                        index in range -> TidalPlayableItems.fromCanonicalUri(
                            appContext, api, uri, qualityPolicy.applyNow(networkTier.get()), mpdCacheDir,
                        )
                        else -> MediaItem.Builder().setMediaId(uri).setUri(Uri.EMPTY).build()
                    }
                }
                mainHandler.post {
                    streamBanker.cancel()
                    // Match Controller: new play clears shuffle/repeat.
                    shuffle = false
                    repeat = RepeatMode.OFF
                    player.shuffleModeEnabled = false
                    player.repeatMode = Player.REPEAT_MODE_OFF
                    player.setMediaItems(items, start, 0L)
                    player.prepare()
                    player.playWhenReady = true
                    refreshQueue()
                    ensureResolvedAround(start, ahead)
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
        when (repeat) {
            RepeatMode.TRACK -> {
                player.seekTo(0L)
                player.playWhenReady = true
            }
            else -> {
                val timeline = player.currentTimeline
                if (timeline.isEmpty) return@onPlayer
                val next = timeline.getNextWindowIndex(
                    player.currentMediaItemIndex,
                    Player.REPEAT_MODE_OFF,
                    /* shuffleModeEnabled= */ false,
                )
                if (next == C.INDEX_UNSET) {
                    if (repeat == RepeatMode.CONTEXT && player.mediaItemCount > 0) {
                        seekToResolvedIndex(0)
                    }
                    return@onPlayer
                }
                seekToResolvedIndex(next)
            }
        }
    }
    override fun previous() = onPlayer {
        if (player.currentPosition > 3_000L) {
            player.seekTo(0L)
            return@onPlayer
        }
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return@onPlayer
        val prev = timeline.getPreviousWindowIndex(
            player.currentMediaItemIndex,
            Player.REPEAT_MODE_OFF,
            /* shuffleModeEnabled= */ false,
        )
        if (prev == C.INDEX_UNSET) {
            player.seekTo(0L)
            return@onPlayer
        }
        seekToResolvedIndex(prev)
    }
    override fun seek(positionMs: UInt) = onPlayer {
        player.seekTo(positionMs.toLong())
        listener?.onPositionChanged(player.currentPosition)
        maybeReportDuration()
        // Keep polling for a bit even if playWhenReady is false after seek settles.
        mainHandler.removeCallbacks(positionPoller)
        mainHandler.post(positionPoller)
    }

    // --- queue --------------------------------------------------------------

    override fun getQueue(): QueueSnapshot = cachedQueue

    override fun addToQueue(uri: String) {
        resolveExecutor.execute {
            try {
                val item = TidalPlayableItems.tryOfflineMediaItem(appContext, uri)
                    ?: TidalPlayableItems.fromCanonicalUri(
                        appContext, api, uri, resolveQuality(), mpdCacheDir,
                    )
                onPlayer {
                    manualMediaIds.add(uri)
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

    override fun applyQueueMoves(ops: List<QueueMoveOp>) {
        if (ops.isEmpty()) return
        onPlayer {
            deferQueueNotify = true
            try {
                for (op in ops) {
                    moveInSublist(
                        uri = op.uri,
                        hint = op.indexHint,
                        manual = op.section == QueueMoveSection.MANUAL,
                        up = op.up,
                    )
                }
            } finally {
                deferQueueNotify = false
                refreshQueue()
            }
        }
    }

    // --- modes --------------------------------------------------------------

    override fun getShuffle(): Boolean = shuffle
    override fun toggleShuffle(): Boolean {
        shuffle = !shuffle
        onPlayer {
            // Never use ExoPlayer global shuffle — it would scramble the manual queue.
            player.shuffleModeEnabled = false
            if (shuffle) {
                shuffleUpcomingContext()
            } else {
                // Linear restore isn't tracked; leave current order (context was shuffled in place).
            }
            refreshQueue()
        }
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

    /** Fisher–Yates shuffle of upcoming non-manual items only. */
    private fun shuffleUpcomingContext() {
        val current = player.currentMediaItemIndex
        if (current < 0) return
        val contextIndices = mutableListOf<Int>()
        for (i in (current + 1) until player.mediaItemCount) {
            if (player.getMediaItemAt(i).mediaId !in manualMediaIds) {
                contextIndices.add(i)
            }
        }
        if (contextIndices.size <= 1) return
        val items = contextIndices.map { player.getMediaItemAt(it) }.toMutableList()
        items.shuffle()
        // Replace from back to front so indices stay valid, or remove+insert block.
        for (i in contextIndices.indices.reversed()) {
            player.removeMediaItem(contextIndices[i])
        }
        val insertAt = insertionIndexForManual().coerceAtLeast(current + 1)
        // After removing context, manuals may still sit after current; insert context after manuals.
        var at = insertAt
        while (at < player.mediaItemCount && player.getMediaItemAt(at).mediaId in manualMediaIds) {
            at++
        }
        player.addMediaItems(at, items)
    }

    // --- settings -----------------------------------------------------------

    /** Spotify-shaped API (unused for TIDAL URIs); kept for [PlaybackBackend] parity. */
    override fun getStreamingQuality(): StreamingQuality = when (qualityPolicy.userQuality()) {
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

    fun getTidalAudioQuality(): TidalAudioQuality = qualityPolicy.userQuality()

    fun setTidalAudioQuality(quality: TidalAudioQuality) {
        // Deferred: current MediaItem keeps its cacheKey/URL until next resolve.
        qualityPolicy.setUserQuality(quality)
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
        runCatching { TidalMediaCache.clearStreamCache(appContext) }
    }

    /**
     * Bank the remainder of the current track into the stream LRU (primary cellular goal).
     * Progressive → CacheWriter; ClearDash → DashDownloader. Playback outranks the banker.
     */
    override fun bufferCurrentToEnd() {
        // Mark busy before posting onto the player looper so StreamingPolicy's
        // awaitBankIdle cannot race ahead of submit().
        streamBanker.markPending()
        onPlayer {
            val item = player.currentMediaItem
            if (item == null) {
                streamBanker.clearPendingIfIdle()
                return@onPlayer
            }
            if (needsResolve(item)) {
                // Resolve first, then bank once the playable item is in place.
                val index = player.currentMediaItemIndex
                val canonical = item.mediaId
                if (!canonical.startsWith("tidal:")) {
                    streamBanker.clearPendingIfIdle()
                    return@onPlayer
                }
                resolveExecutor.execute {
                    try {
                        val playable = TidalPlayableItems.fromCanonicalUri(
                            appContext, api, canonical, resolveQuality(), mpdCacheDir,
                        )
                        mainHandler.post {
                            if (index < player.mediaItemCount &&
                                player.getMediaItemAt(index).mediaId == canonical
                            ) {
                                if (needsResolve(player.getMediaItemAt(index))) {
                                    player.replaceMediaItem(index, playable)
                                }
                                val current = player.getMediaItemAt(index)
                                streamBanker.bankCurrentToEnd(current, player.currentPosition)
                            } else {
                                streamBanker.clearPendingIfIdle()
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("TidalPlayback", "bank resolve failed for $canonical", e)
                        streamBanker.clearPendingIfIdle()
                    }
                }
                return@onPlayer
            }
            streamBanker.bankCurrentToEnd(item, player.currentPosition)
        }
    }

    override fun awaitBankIdle(timeoutMs: Long): Boolean =
        streamBanker.awaitBankIdle(timeoutMs)

    /**
     * Look-ahead: JIT resolve next [ahead] URLs/MPDs, then byte-warm the immediate
     * next track into the stream LRU (after current bank — see StreamingPolicy).
     */
    override fun prefetchUpcoming(ahead: UInt) {
        val depth = ahead.toInt().coerceIn(0, 3)
        resolveAhead.set(depth)
        if (depth <= 0) return
        onPlayer {
            ensureResolvedAround(player.currentMediaItemIndex, depth) {
                warmNextResolved()
            }
        }
    }

    /** Byte-warm the next timeline item if already resolved (CacheWriter / DashDownloader). */
    private fun warmNextResolved() {
        onPlayer {
            val timeline = player.currentTimeline
            if (timeline.isEmpty) return@onPlayer
            val next = timeline.getNextWindowIndex(
                player.currentMediaItemIndex,
                player.repeatMode,
                player.shuffleModeEnabled,
            )
            if (next == C.INDEX_UNSET) return@onPlayer
            val item = player.getMediaItemAt(next)
            if (needsResolve(item)) return@onPlayer
            // Don't fight playback with multi-track warm; next-1 only.
            streamBanker.warmItem(item)
        }
    }

    // --- internals ----------------------------------------------------------

    private fun needsResolve(item: MediaItem): Boolean {
        val uri = item.localConfiguration?.uri ?: return true
        return uri == Uri.EMPTY || uri.scheme == TidalMediaCache.STREAM_SCHEME
    }

    /** Snapshot placeholders on the main thread, resolve on the worker, replace on main. */
    private fun ensureResolvedAround(
        index: Int,
        ahead: Int = resolveAhead.get(),
        onResolved: (() -> Unit)? = null,
    ) {
        val count = player.mediaItemCount
        if (count == 0) {
            onResolved?.invoke()
            return
        }
        val indices = TidalPrefetchWindows.resolvedIndices(
            timeline = player.currentTimeline,
            from = index,
            ahead = ahead,
            repeatMode = player.repeatMode,
            shuffleModeEnabled = player.shuffleModeEnabled,
        )
        val pending = buildList {
            for (i in indices) {
                if (i !in 0 until count) continue
                val item = player.getMediaItemAt(i)
                if (needsResolve(item) && item.mediaId.startsWith("tidal:")) {
                    add(i to item.mediaId)
                }
            }
        }
        if (pending.isEmpty()) {
            onResolved?.invoke()
            return
        }
        val quality = resolveQuality()
        resolveExecutor.execute {
            for ((i, canonical) in pending) {
                try {
                    val playable = TidalPlayableItems.tryOfflineMediaItem(appContext, canonical)
                        ?: TidalPlayableItems.fromCanonicalUri(
                            appContext, api, canonical, quality, mpdCacheDir,
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
            mainHandler.post { onResolved?.invoke() }
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
                val playable = TidalPlayableItems.tryOfflineMediaItem(appContext, canonical)
                    ?: TidalPlayableItems.fromCanonicalUri(
                        appContext, api, canonical, resolveQuality(), mpdCacheDir,
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

    private fun moveInSublist(uri: String, hint: Int, manual: Boolean, up: Boolean) {
        val slots = upcomingSlots(manual)
        val ids = slots.map { player.getMediaItemAt(it).mediaId }
        val index = QueueMoveIndex.resolve(ids, uri, hint) ?: return
        val from = slots[index]
        if (manual && !up && index == slots.lastIndex) {
            // Demote last manual into front of context.
            val id = player.getMediaItemAt(from).mediaId
            manualMediaIds.remove(id)
            val item = player.getMediaItemAt(from)
            player.removeMediaItem(from)
            var insertAt = player.currentMediaItemIndex + 1
            while (insertAt < player.mediaItemCount &&
                player.getMediaItemAt(insertAt).mediaId in manualMediaIds
            ) {
                insertAt++
            }
            player.addMediaItem(insertAt, item)
            return
        }
        if (!manual && up && index == 0) {
            // Promote first context into end of manual queue.
            val id = player.getMediaItemAt(from).mediaId
            manualMediaIds.add(id)
            val item = player.getMediaItemAt(from)
            player.removeMediaItem(from)
            val insertAt = insertionIndexForManual()
            player.addMediaItem(insertAt, item)
            return
        }
        val neighborIdx = if (up) index - 1 else index + 1
        if (neighborIdx !in slots.indices) return
        val to = slots[neighborIdx]
        player.moveMediaItem(from, to)
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
        if (!deferQueueNotify) listener?.onQueueChanged()
    }

    /**
     * Run [block] on the player's (main) thread and wait until it finishes.
     * ExoPlayer playlist mutations are not safe to fire-and-forget from IO —
     * the controller serializes on the return of this call.
     */
    private fun onPlayer(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        val posted = mainHandler.post {
            try {
                block()
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                done.countDown()
            }
        }
        if (!posted) {
            throw IllegalStateException("player thread is gone")
        }
        done.await()
        error.get()?.let { throw it }
    }

    private val positionPoller = object : Runnable {
        override fun run() {
            listener?.onPositionChanged(player.currentPosition)
            maybeReportDuration()
            if (player.isPlaying || player.playbackState == Player.STATE_BUFFERING) {
                mainHandler.postDelayed(this, POSITION_POLL_MS)
            }
        }
    }

    private fun maybeReportDuration() {
        val d = player.duration
        if (d > 0L) listener?.onDurationMs(d)
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            streamBanker.cancel()
            val applied = qualityPolicy.commitPending(networkTier.get())
            mediaItem?.mediaId?.let { uri ->
                cdnRefreshAttempts.clear(uri)
                listener?.onTrackChanged(uri)
                playReporter.onTrackStarted(
                    uri = uri,
                    qualityApiValue = applied.apiValue,
                    durationMs = player.duration.takeIf { it > 0 } ?: 0L,
                )
                maybeReportDuration()
            }
            refreshQueue()
            ensureResolvedAround(player.currentMediaItemIndex, resolveAhead.get())
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> {
                    listener?.onLoading()
                    listener?.onBuffering(true)
                    mainHandler.removeCallbacks(positionPoller)
                    mainHandler.post(positionPoller)
                }
                Player.STATE_READY -> {
                    listener?.onBuffering(false)
                    maybeReportDuration()
                    listener?.onPositionChanged(player.currentPosition)
                }
                Player.STATE_ENDED -> {
                    playReporter.onTrackStopped()
                    listener?.onEndOfTrack()
                }
                Player.STATE_IDLE -> {}
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                playReporter.onPlaying()
                listener?.onPlaying(player.currentPosition)
                maybeReportDuration()
                mainHandler.removeCallbacks(positionPoller)
                mainHandler.post(positionPoller)
            } else {
                playReporter.onPaused()
                listener?.onPaused(player.currentPosition)
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
            ) {
                maybeReportDuration()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val mediaItem = player.currentMediaItem
            val mediaId = mediaItem?.mediaId
            if (mediaId != null &&
                mediaId.startsWith("tidal:") &&
                CdnUrlRefresher.isCdnAuthFailure(error) &&
                cdnRefreshAttempts.tryBegin(mediaId)
            ) {
                android.util.Log.w(
                    "TidalPlayback",
                    "CDN auth failure — re-resolving $mediaId (same cache key)",
                )
                listener?.onLoading()
                val index = player.currentMediaItemIndex
                val positionMs = player.currentPosition.coerceAtLeast(0L)
                val playWhenReady = player.playWhenReady
                resolveExecutor.execute {
                    try {
                        // Keep applied quality so cacheKey stays stable across URL mint.
                        val playable = TidalPlayableItems.fromCanonicalUri(
                            appContext,
                            api,
                            mediaId,
                            qualityPolicy.appliedQuality(),
                            mpdCacheDir,
                        )
                        mainHandler.post {
                            if (index < player.mediaItemCount &&
                                player.getMediaItemAt(index).mediaId == mediaId
                            ) {
                                player.replaceMediaItem(index, playable)
                                player.seekTo(index, positionMs)
                                player.playWhenReady = playWhenReady
                                player.prepare()
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("TidalPlayback", "CDN re-resolve failed for $mediaId", e)
                        mainHandler.post {
                            listener?.onUnavailable(mediaId)
                            listener?.onError(
                                humanizePlaybackError(e.message ?: "Couldn't refresh stream"),
                            )
                        }
                    }
                }
                return
            }

            mediaId?.let { listener?.onUnavailable(it) }
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

        private val TIDAL_QUALITY_LADDER = listOf(
            TidalAudioQuality.EXTRA_LOW,
            TidalAudioQuality.LOW,
            TidalAudioQuality.HIGH,
            TidalAudioQuality.MAX,
        )

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
            raw.contains("Unable to resolve host", ignoreCase = true) ||
                raw.contains("UnknownHost", ignoreCase = true) ||
                raw.contains("failed to connect", ignoreCase = true) ||
                raw.contains("Network is unreachable", ignoreCase = true) ||
                raw.contains("not available offline", ignoreCase = true) ->
                "Not available offline."
            raw.equals("Source error", ignoreCase = true) ->
                "Couldn't open stream. Try another quality or re-login."
            else -> raw.take(160)
        }
    }
}
