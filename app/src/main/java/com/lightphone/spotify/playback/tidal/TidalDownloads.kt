package com.lightphone.spotify.playback.tidal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.lightphone.spotify.data.TrackMetadata
import com.lightphone.spotify.data.local.DownloadedCollectionEntity
import com.lightphone.spotify.data.local.DownloadedCollectionTrackEntity
import com.lightphone.spotify.data.local.DownloadedTrackDao
import com.lightphone.spotify.data.local.DownloadedTrackEntity
import com.lightphone.spotify.data.local.PhonoDatabase
import com.lightphone.spotify.data.tidal.TidalApiClient
import com.lightphone.spotify.data.tidal.TidalAuth
import com.lightphone.spotify.data.tidal.TidalUri
import java.io.File
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * TIDAL offline downloads via Media3 [DownloadManager].
 *
 * Supports progressive BTS and clear DASH (sanitized MPD). Widevine/encrypted
 * streams are skipped. Changing download quality never rewrites completed pins.
 *
 * Enqueue is serialized through one mutex to avoid EncryptedSharedPreferences /
 * DownloadService FGS storms that previously killed the process mid-album.
 * Collection resolves are staggered (jitter) and Media3 runs one CDN pin at a
 * time — same pacing mature downloaders use to avoid playbackinfo 429 storms.
 *
 * CDN 403/401 is terminal for Media3; we re-resolve + re-enqueue (capped).
 */
@UnstableApi
object TidalDownloadCenter {
    private const val TAG = "TidalDownloads"
    private const val NOTIFICATION_CHANNEL_ID = "phono_downloads"
    private const val NOTIFICATION_ID = 0x70646c00 // "pdl"
    /** Refuse new collection enqueues below this free space (matches Tide). */
    private const val MIN_FREE_BYTES = 150L * 1024 * 1024
    private const val STAGGER_MIN_MS = 400L
    private const val STAGGER_MAX_MS = 1200L
    private const val CDN_RETRY_MAX = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val enqueueMutex = Mutex()
    private val cdnRetryCounts = ConcurrentHashMap<String, Int>()
    /** Media3 remove during CDN retry must not wipe the Room stub. */
    private val suppressRoomDeleteIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var sharedAuth: TidalAuth? = null

    @Volatile
    private var sharedApi: TidalApiClient? = null

    @Volatile
    private var manager: DownloadManager? = null

    @Volatile
    private var notificationHelper: DownloadNotificationHelper? = null

    /** Bind the session auth/API so downloads do not open EncryptedPrefs per track. */
    fun bind(auth: TidalAuth, api: TidalApiClient) {
        sharedAuth = auth
        sharedApi = api
    }

    /** Restart unfinished downloads after process death (cold start). */
    fun resumeDownloads(context: Context) {
        val app = context.applicationContext
        ensureChannel(app)
        try {
            DownloadService.sendResumeDownloads(
                app,
                TidalDownloadService::class.java,
                /* foreground= */ true,
            )
        } catch (e: Exception) {
            Log.e(TAG, "sendResumeDownloads failed", e)
        }
    }

    fun notificationHelper(context: Context): DownloadNotificationHelper =
        notificationHelper ?: synchronized(this) {
            notificationHelper ?: DownloadNotificationHelper(
                context.applicationContext,
                NOTIFICATION_CHANNEL_ID,
            ).also { notificationHelper = it }
        }

    fun downloadManager(context: Context): DownloadManager {
        manager?.let { return it }
        return synchronized(this) {
            manager ?: buildManager(context.applicationContext).also { manager = it }
        }
    }

    private fun buildManager(appContext: Context): DownloadManager {
        val dm = DownloadManager(
            appContext,
            TidalMediaCache.databaseProvider(appContext),
            TidalMediaCache.cache(appContext),
            TidalMediaCache.downloadUpstreamFactory(appContext),
            Executors.newFixedThreadPool(2),
        )
        // One track CDN at a time; 2 loader threads still serve DASH segments.
        dm.maxParallelDownloads = 1
        val db = PhonoDatabase.get(appContext)
        dm.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?,
            ) {
                if (finalException != null) {
                    Log.e(TAG, "download failed ${download.request.id}", finalException)
                }
                val downloadId = download.request.id
                val uri = canonicalUri(downloadId) ?: return
                scope.launch {
                    db.downloadedTrackDao().updateState(
                        uri = uri,
                        state = download.state,
                        bytes = download.bytesDownloaded,
                        updatedAt = System.currentTimeMillis(),
                    )
                    if (download.state == Download.STATE_COMPLETED) {
                        cdnRetryCounts.remove(downloadId)
                    } else if (download.state == Download.STATE_FAILED &&
                        shouldRetryCdnFailure(finalException)
                    ) {
                        maybeRetryAfterCdnFailure(appContext, downloadId, uri)
                    }
                }
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                val downloadId = download.request.id
                // CDN retry removes the stale Media3 entry; keep the Room stub.
                if (suppressRoomDeleteIds.remove(downloadId)) return
                val uri = canonicalUri(downloadId) ?: return
                scope.launch { db.downloadedTrackDao().delete(uri) }
            }
        })
        return dm
    }

    private fun shouldRetryCdnFailure(e: Exception?): Boolean = when (e) {
        is HttpDataSource.InvalidResponseCodeException ->
            e.responseCode == 403 || e.responseCode == 401
        is SocketTimeoutException, is UnknownHostException, is InterruptedIOException -> true
        else -> false
    }

    private suspend fun maybeRetryAfterCdnFailure(
        appContext: Context,
        downloadId: String,
        trackUri: String,
    ) {
        val attempt = (cdnRetryCounts[downloadId] ?: 0) + 1
        if (attempt > CDN_RETRY_MAX) {
            Log.w(TAG, "cdn retry exhausted for $downloadId")
            return
        }
        cdnRetryCounts[downloadId] = attempt
        val backoffMs = when (attempt) {
            1 -> 2_000L
            2 -> 5_000L
            else -> 10_000L
        }
        Log.i(TAG, "cdn retry $attempt/$CDN_RETRY_MAX for $downloadId after failure; wait ${backoffMs}ms")
        delay(backoffMs)

        val db = PhonoDatabase.get(appContext)
        val row = db.downloadedTrackDao().getByUri(trackUri) ?: return
        val quality = row.quality.ifBlank { "LOSSLESS" }
        val track = TrackMetadata(
            uri = row.uri,
            title = row.title,
            artists = row.artists,
            album = row.album,
            durationMs = 0L,
            artUrl = row.art_url,
        )

        suppressRoomDeleteIds.add(downloadId)
        try {
            runCatching {
                DownloadService.sendRemoveDownload(
                    appContext,
                    TidalDownloadService::class.java,
                    downloadId,
                    /* foreground= */ false,
                )
            }
            // Brief pause so remove settles before re-add of the same id.
            delay(300)
            enqueueTrack(
                appContext,
                track,
                quality,
                upsertStubFirst = true,
                staggerAfter = false,
            )
        } finally {
            // onDownloadRemoved clears the suppress flag; this is a leak-safety fallback.
            scope.launch {
                delay(5_000)
                suppressRoomDeleteIds.remove(downloadId)
            }
        }
    }

    private fun requireApi(context: Context): Pair<TidalAuth, TidalApiClient> {
        sharedAuth?.let { auth ->
            sharedApi?.let { api -> return auth to api }
        }
        // Fallback if bind() was not called yet (e.g. DownloadService alone).
        val auth = TidalAuth(context.applicationContext)
        val api = TidalApiClient(auth)
        sharedAuth = auth
        sharedApi = api
        return auth to api
    }

    /**
     * Pin [track] for offline playback. Skips if already completed or actively
     * downloading at any quality (future-only quality setting).
     */
    fun download(context: Context, track: TrackMetadata, quality: String) {
        val appContext = context.applicationContext
        scope.launch {
            enqueueTrack(appContext, track, quality, upsertStubFirst = true)
        }
    }

    fun downloadCollection(
        context: Context,
        collectionUri: String,
        type: String,
        name: String,
        artUrl: String?,
        tracks: List<TrackMetadata>,
        quality: String,
    ) {
        val appContext = context.applicationContext
        scope.launch {
            val db = PhonoDatabase.get(appContext)
            val collections = db.downloadedCollectionDao()
            val trackDao = db.downloadedTrackDao()
            // Persist collection + memberships + track stubs BEFORE any network /
            // DownloadService work so a crash mid-resolve still leaves a usable list.
            collections.upsert(
                DownloadedCollectionEntity(
                    uri = collectionUri,
                    type = type,
                    name = name,
                    art_url = artUrl,
                    updated_at = System.currentTimeMillis(),
                ),
            )
            tracks.forEachIndexed { index, track ->
                collections.upsertMembership(
                    DownloadedCollectionTrackEntity(
                        collection_uri = collectionUri,
                        track_uri = track.uri,
                        position = index,
                    ),
                )
                val existing = trackDao.getByUri(track.uri)
                if (existing == null || !shouldSkipEnqueue(existing.state)) {
                    trackDao.upsert(
                        DownloadedTrackEntity(
                            uri = track.uri,
                            title = track.title,
                            artists = track.artists,
                            album = track.album,
                            art_url = track.artUrl,
                            quality = quality,
                            state = Download.STATE_QUEUED,
                            bytes = 0,
                            updated_at = System.currentTimeMillis(),
                        ),
                    )
                }
            }
            if (insufficientFreeSpace(appContext)) {
                Log.w(
                    TAG,
                    "abort collection $collectionUri — free space " +
                        "${appContext.filesDir.usableSpace} < $MIN_FREE_BYTES",
                )
                failQueuedTracks(trackDao, tracks, quality)
                return@launch
            }
            // Serialize resolve + enqueue so we never open N EncryptedPrefs /
            // start N FGS races at once. Stagger playbackinfo calls between tracks.
            for ((index, track) in tracks.withIndex()) {
                if (insufficientFreeSpace(appContext)) {
                    Log.w(TAG, "stop collection enqueue mid-album — low free space")
                    failQueuedTracks(trackDao, tracks.drop(index), quality)
                    break
                }
                val staggerAfter = index < tracks.lastIndex
                enqueueTrack(
                    appContext,
                    track,
                    quality,
                    upsertStubFirst = false,
                    staggerAfter = staggerAfter,
                )
            }
        }
    }

    private suspend fun enqueueTrack(
        appContext: Context,
        track: TrackMetadata,
        quality: String,
        upsertStubFirst: Boolean,
        staggerAfter: Boolean = false,
    ) = enqueueMutex.withLock {
        var didResolveAttempt = false
        try {
            val db = PhonoDatabase.get(appContext)
            val existing = db.downloadedTrackDao().getByUri(track.uri)
            when (existing?.state) {
                Download.STATE_COMPLETED,
                Download.STATE_DOWNLOADING,
                Download.STATE_RESTARTING,
                -> {
                    Log.i(TAG, "skip ${track.uri} — already state=${existing.state} @ ${existing.quality}")
                    return@withLock
                }
                Download.STATE_QUEUED -> {
                    // Stub from downloadCollection, or a prior enqueue that died before Media3
                    // started. Only skip if Media3 already owns this download id.
                    val id = TidalUri.rawId(track.uri)
                    val downloadId = "tidal:$id:${existing.quality.ifBlank { quality }}"
                    val already = runCatching {
                        downloadManager(appContext).downloadIndex.getDownload(downloadId)
                    }.getOrNull()
                    if (already != null &&
                        already.state != Download.STATE_FAILED &&
                        already.state != Download.STATE_REMOVING
                    ) {
                        Log.i(TAG, "skip ${track.uri} — Media3 already has $downloadId")
                        return@withLock
                    }
                }
                else -> Unit
            }

            if (upsertStubFirst) {
                db.downloadedTrackDao().upsert(
                    DownloadedTrackEntity(
                        uri = track.uri,
                        title = track.title,
                        artists = track.artists,
                        album = track.album,
                        art_url = track.artUrl,
                        quality = quality,
                        state = Download.STATE_QUEUED,
                        bytes = 0,
                        updated_at = System.currentTimeMillis(),
                    ),
                )
            }

            val (auth, api) = requireApi(appContext)
            runCatching { auth.ensureSessionMeta() }
            val id = TidalUri.rawId(track.uri)
            didResolveAttempt = true
            val resolved = try {
                withContext(Dispatchers.IO) {
                    TidalStreamResolve.resolve(api, id, quality)
                }
            } catch (e: Exception) {
                Log.e(TAG, "resolve failed for ${track.uri}", e)
                db.downloadedTrackDao().upsert(
                    DownloadedTrackEntity(
                        uri = track.uri,
                        title = track.title,
                        artists = track.artists,
                        album = track.album,
                        art_url = track.artUrl,
                        quality = quality,
                        state = Download.STATE_FAILED,
                        bytes = 0,
                        updated_at = System.currentTimeMillis(),
                    ),
                )
                return@withLock
            }

            val pinnedQuality = resolved.audioQuality.ifBlank { quality }
            db.downloadedTrackDao().upsert(
                DownloadedTrackEntity(
                    uri = track.uri,
                    title = track.title,
                    artists = track.artists,
                    album = track.album,
                    art_url = track.artUrl,
                    quality = pinnedQuality,
                    state = Download.STATE_QUEUED,
                    bytes = 0,
                    updated_at = System.currentTimeMillis(),
                ),
            )

            ensureChannel(appContext)
            val request = try {
                buildDownloadRequest(appContext, id, resolved)
            } catch (e: Exception) {
                Log.e(TAG, "buildDownloadRequest failed for ${track.uri}", e)
                db.downloadedTrackDao().upsert(
                    DownloadedTrackEntity(
                        uri = track.uri,
                        title = track.title,
                        artists = track.artists,
                        album = track.album,
                        art_url = track.artUrl,
                        quality = pinnedQuality,
                        state = Download.STATE_FAILED,
                        bytes = 0,
                        updated_at = System.currentTimeMillis(),
                    ),
                )
                return@withLock
            }
            try {
                DownloadService.sendAddDownload(
                    appContext,
                    TidalDownloadService::class.java,
                    request,
                    /* foreground= */ true,
                )
            } catch (e: Exception) {
                Log.e(TAG, "sendAddDownload failed for ${track.uri}", e)
                db.downloadedTrackDao().upsert(
                    DownloadedTrackEntity(
                        uri = track.uri,
                        title = track.title,
                        artists = track.artists,
                        album = track.album,
                        art_url = track.artUrl,
                        quality = pinnedQuality,
                        state = Download.STATE_FAILED,
                        bytes = 0,
                        updated_at = System.currentTimeMillis(),
                    ),
                )
            }
        } finally {
            if (staggerAfter && didResolveAttempt) {
                delay(Random.nextLong(STAGGER_MIN_MS, STAGGER_MAX_MS + 1))
            }
        }
    }

    /** Mark non-completed stubs failed so detail UI shows failure, not forever-Queued. */
    private suspend fun failQueuedTracks(
        trackDao: DownloadedTrackDao,
        tracks: List<TrackMetadata>,
        quality: String,
    ) {
        val now = System.currentTimeMillis()
        for (track in tracks) {
            val existing = trackDao.getByUri(track.uri)
            when (existing?.state) {
                Download.STATE_COMPLETED,
                Download.STATE_DOWNLOADING,
                Download.STATE_RESTARTING,
                -> continue
                else -> Unit
            }
            trackDao.upsert(
                DownloadedTrackEntity(
                    uri = track.uri,
                    title = track.title,
                    artists = track.artists,
                    album = track.album,
                    art_url = track.artUrl,
                    quality = existing?.quality?.ifBlank { quality } ?: quality,
                    state = Download.STATE_FAILED,
                    bytes = existing?.bytes ?: 0,
                    updated_at = now,
                ),
            )
        }
    }

    /**
     * True when free space is known and below [MIN_FREE_BYTES].
     * `usableSpace == 0` is treated as unknown (do not block), matching Tide.
     */
    private fun insufficientFreeSpace(context: Context): Boolean {
        val free = runCatching { context.filesDir.usableSpace }.getOrDefault(0L)
        return free in 1 until MIN_FREE_BYTES
    }

    fun remove(context: Context, track: TrackMetadata, quality: String) {
        val id = TidalUri.rawId(track.uri)
        try {
            DownloadService.sendRemoveDownload(
                context.applicationContext,
                TidalDownloadService::class.java,
                "tidal:$id:$quality",
                /* foreground= */ false,
            )
        } catch (e: Exception) {
            Log.e(TAG, "sendRemoveDownload failed for ${track.uri}", e)
        }
    }

    fun removeCollection(context: Context, collectionUri: String) {
        val appContext = context.applicationContext
        scope.launch {
            val db = PhonoDatabase.get(appContext)
            val collections = db.downloadedCollectionDao()
            val memberships = collections.trackUrisForCollection(collectionUri)
            collections.deleteCollection(collectionUri)
            for (trackUri in memberships) {
                if (collections.membershipCountForTrack(trackUri) == 0) {
                    val row = db.downloadedTrackDao().getByUri(trackUri) ?: continue
                    remove(
                        appContext,
                        TrackMetadata(
                            uri = row.uri,
                            title = row.title,
                            artists = row.artists,
                            album = row.album,
                            durationMs = 0L,
                            artUrl = row.art_url,
                        ),
                        row.quality,
                    )
                }
            }
        }
    }

    private fun buildDownloadRequest(
        context: Context,
        trackId: String,
        resolved: TidalResolvedStream,
    ): DownloadRequest {
        val downloadId = resolved.cacheKey
        return when (resolved) {
            // Progressive (CONTENT_TYPE_OTHER): customCacheKey is allowed and preferred —
            // TIDAL CDN URLs are signed/ephemeral; a stable key keeps cache hits across resolves.
            // See Media3 DownloadRequestTest mergeRequest_withSameRequest (progressive + key).
            is TidalResolvedStream.Progressive ->
                DownloadRequest.Builder(downloadId, Uri.parse(resolved.url))
                    .setCustomCacheKey(resolved.cacheKey)
                    .build()
            // DASH (CONTENT_TYPE_DASH == 0): Media3 1.9 requires customCacheKey == null.
            // Docs: "Must be null for DASH, HLS and SmoothStreaming downloads."
            // Mime hint is required so file://…mpd is inferred as DASH, not OTHER.
            is TidalResolvedStream.ClearDash -> {
                val dir = TidalMediaCache.mpdDir(context)
                val file = File(dir, "${trackId}_${resolved.audioQuality}.mpd")
                file.writeText(resolved.mpdXml)
                DownloadRequest.Builder(downloadId, Uri.fromFile(file))
                    .setMimeType(MimeTypes.APPLICATION_MPD)
                    .build()
            }
        }
    }

    /** `tidal:{id}:{quality}` download id -> canonical `tidal:track:{id}` uri. */
    private fun canonicalUri(downloadId: String): String? {
        val parts = downloadId.split(":")
        if (parts.size < 2) return null
        return "tidal:track:${parts[1]}"
    }

    private fun shouldSkipEnqueue(state: Int): Boolean = when (state) {
        Download.STATE_COMPLETED,
        Download.STATE_DOWNLOADING,
        Download.STATE_QUEUED,
        Download.STATE_RESTARTING,
        -> true
        else -> false
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Downloads",
            NotificationManager.IMPORTANCE_LOW,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun foregroundNotificationId(): Int = NOTIFICATION_ID
}

/**
 * Media3 [DownloadService] host for TIDAL offline downloads. Registered in the
 * manifest; started implicitly by [TidalDownloadCenter.download].
 */
@UnstableApi
class TidalDownloadService : DownloadService(
    TidalDownloadCenter.foregroundNotificationId(),
) {
    override fun getDownloadManager(): DownloadManager {
        TidalDownloadCenter.ensureChannel(this)
        return TidalDownloadCenter.downloadManager(this)
    }

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int,
    ): Notification {
        return TidalDownloadCenter.notificationHelper(this).buildProgressNotification(
            this,
            android.R.drawable.stat_sys_download,
            null,
            null,
            downloads,
            notMetRequirements,
        )
    }
}
