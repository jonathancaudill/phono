package com.lightphone.spotify.playback.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.lightphone.spotify.data.TrackMetadata
import com.lightphone.spotify.data.local.DownloadedCollectionEntity
import com.lightphone.spotify.data.local.DownloadedCollectionTrackEntity
import com.lightphone.spotify.data.local.DownloadedTrackDao
import com.lightphone.spotify.data.local.DownloadedTrackEntity
import com.lightphone.spotify.data.local.PhonoDatabase
import com.lightphone.spotify.ffi.LibrespotEngine
import com.lightphone.spotify.ffi.StreamingQuality
import com.lightphone.spotify.playback.PlaybackEngineHolder
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Spotify offline pins via UniFFI [LibrespotEngine.downloadTrack] (decrypt-to-Ogg).
 * Mirrors TIDAL operational maturity: Room stubs first, enqueue mutex, stagger,
 * free-space gate, CDN/session retry, cold-start resume.
 */
object SpotifyDownloadCenter : OfflineDownloadCenter {
    private const val TAG = "SpotifyDownloads"
    private const val NOTIFICATION_CHANNEL_ID = "phono_downloads"
    private const val NOTIFICATION_ID = 0x70647370 // "pdsp"
    private const val MIN_FREE_BYTES = 150L * 1024 * 1024
    private const val STAGGER_MIN_MS = 400L
    private const val STAGGER_MAX_MS = 1200L
    private const val RETRY_MAX = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val enqueueMutex = Mutex()
    private val retryCounts = ConcurrentHashMap<String, Int>()

    @Volatile
    private var engineProvider: (() -> LibrespotEngine?)? = null

    override val supported: Boolean = true

    fun bindEngine(provider: () -> LibrespotEngine?) {
        engineProvider = provider
    }

    override fun resumeDownloads(context: Context) {
        val app = context.applicationContext
        ensureChannel(app)
        try {
            val intent = Intent(app, SpotifyDownloadService::class.java).apply {
                action = SpotifyDownloadService.ACTION_RESUME
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "resumeDownloads failed", e)
        }
    }

    override fun download(context: Context, track: TrackMetadata, quality: String) {
        val app = context.applicationContext
        scope.launch {
            val db = PhonoDatabase.get(app)
            val existing = db.downloadedTrackDao().getByUri(track.uri)
            if (existing != null && DownloadStates.shouldSkipEnqueue(existing.state)) {
                Log.i(TAG, "skip ${track.uri} — state=${existing.state}")
                return@launch
            }
            db.downloadedTrackDao().upsert(
                DownloadedTrackEntity(
                    uri = track.uri,
                    title = track.title,
                    artists = track.artists,
                    album = track.album,
                    art_url = track.artUrl,
                    quality = quality,
                    state = DownloadStates.QUEUED,
                    bytes = 0,
                    updated_at = System.currentTimeMillis(),
                    duration_ms = track.durationMs,
                ),
            )
            kickService(app)
        }
    }

    override fun downloadCollection(
        context: Context,
        collectionUri: String,
        type: String,
        name: String,
        artUrl: String?,
        tracks: List<TrackMetadata>,
        quality: String,
    ) {
        val app = context.applicationContext
        scope.launch {
            val db = PhonoDatabase.get(app)
            val collections = db.downloadedCollectionDao()
            val trackDao = db.downloadedTrackDao()
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
                if (existing == null || !DownloadStates.shouldSkipEnqueue(existing.state)) {
                    trackDao.upsert(
                        DownloadedTrackEntity(
                            uri = track.uri,
                            title = track.title,
                            artists = track.artists,
                            album = track.album,
                            art_url = track.artUrl,
                            quality = quality,
                            state = DownloadStates.QUEUED,
                            bytes = 0,
                            updated_at = System.currentTimeMillis(),
                            duration_ms = track.durationMs,
                        ),
                    )
                }
            }
            if (insufficientFreeSpace(app)) {
                Log.w(TAG, "abort collection $collectionUri — low free space")
                failQueuedTracks(trackDao, tracks, quality)
                return@launch
            }
            kickService(app)
        }
    }

    override fun remove(context: Context, track: TrackMetadata, quality: String) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                requireEngine(app)?.removeDownload(track.uri)
            }.onFailure { e -> Log.e(TAG, "remove_download native failed", e) }
            PhonoDatabase.get(app).downloadedTrackDao().delete(track.uri)
        }
    }

    override fun removeCollection(context: Context, collectionUri: String) {
        val app = context.applicationContext
        scope.launch {
            val db = PhonoDatabase.get(app)
            val collections = db.downloadedCollectionDao()
            val memberships = collections.trackUrisForCollection(collectionUri)
            collections.deleteCollection(collectionUri)
            for (trackUri in memberships) {
                if (collections.membershipCountForTrack(trackUri) == 0) {
                    val row = db.downloadedTrackDao().getByUri(trackUri) ?: continue
                    remove(
                        app,
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

    /**
     * Drain one queued track (called from [SpotifyDownloadService]).
     * @return true if work remains
     */
    suspend fun processNext(context: Context): Boolean {
        val app = context.applicationContext
        if (insufficientFreeSpace(app)) {
            Log.w(TAG, "stop drain — low free space")
            return false
        }
        val db = PhonoDatabase.get(app)
        val dao = db.downloadedTrackDao()
        val next = dao.getAll().firstOrNull { it.state == DownloadStates.QUEUED }
            ?: return false
        val track = TrackMetadata(
            uri = next.uri,
            title = next.title,
            artists = next.artists,
            album = next.album,
            durationMs = 0L,
            artUrl = next.art_url,
        )
        enqueueTrack(
            app,
            track,
            next.quality.ifBlank { StreamingQuality.HIGH.name },
            staggerAfter = true,
        )
        return dao.getAll().any { it.state == DownloadStates.QUEUED }
    }

    private suspend fun enqueueTrack(
        appContext: Context,
        track: TrackMetadata,
        quality: String,
        staggerAfter: Boolean = false,
    ) = enqueueMutex.withLock {
        var didAttempt = false
        try {
            val db = PhonoDatabase.get(appContext)
            val existing = db.downloadedTrackDao().getByUri(track.uri)
            when (existing?.state) {
                DownloadStates.COMPLETED,
                DownloadStates.DOWNLOADING,
                DownloadStates.RESTARTING,
                -> {
                    Log.i(TAG, "skip ${track.uri} — state=${existing.state}")
                    return@withLock
                }
                else -> Unit
            }

            val engine = requireEngine(appContext)
            if (engine == null) {
                Log.e(TAG, "no engine for ${track.uri}")
                markFailed(db.downloadedTrackDao(), track, quality, 0)
                return@withLock
            }

            didAttempt = true
            db.downloadedTrackDao().updateState(
                uri = track.uri,
                state = DownloadStates.DOWNLOADING,
                bytes = existing?.bytes ?: 0,
                updatedAt = System.currentTimeMillis(),
            )

            val sq = streamingQualityFromApi(quality)
            val result = runCatching {
                engine.downloadTrack(track.uri, sq)
            }
            result.fold(
                onSuccess = { info ->
                    retryCounts.remove(track.uri)
                    db.downloadedTrackDao().upsert(
                        DownloadedTrackEntity(
                            uri = track.uri,
                            title = track.title,
                            artists = track.artists,
                            album = track.album,
                            art_url = track.artUrl,
                            quality = info.quality.ifBlank { quality },
                            state = DownloadStates.COMPLETED,
                            bytes = info.bytes.toLong(),
                            updated_at = System.currentTimeMillis(),
                            duration_ms = track.durationMs,
                        ),
                    )
                },
                onFailure = { e ->
                    Log.e(TAG, "download failed ${track.uri}", e)
                    val attempt = (retryCounts[track.uri] ?: 0) + 1
                    if (attempt <= RETRY_MAX) {
                        retryCounts[track.uri] = attempt
                        val backoff = when (attempt) {
                            1 -> 2_000L
                            2 -> 5_000L
                            else -> 10_000L
                        }
                        delay(backoff)
                        db.downloadedTrackDao().updateState(
                            uri = track.uri,
                            state = DownloadStates.QUEUED,
                            bytes = 0,
                            updatedAt = System.currentTimeMillis(),
                        )
                    } else {
                        markFailed(db.downloadedTrackDao(), track, quality, 0)
                    }
                },
            )
        } finally {
            if (staggerAfter && didAttempt) {
                delay(Random.nextLong(STAGGER_MIN_MS, STAGGER_MAX_MS + 1))
            }
        }
    }

    private fun requireEngine(context: Context): LibrespotEngine? {
        engineProvider?.invoke()?.let { return it }
        return runCatching { PlaybackEngineHolder.createEngine(context) }.getOrNull()
    }

    private suspend fun markFailed(
        dao: DownloadedTrackDao,
        track: TrackMetadata,
        quality: String,
        bytes: Long,
    ) {
        dao.upsert(
            DownloadedTrackEntity(
                uri = track.uri,
                title = track.title,
                artists = track.artists,
                album = track.album,
                art_url = track.artUrl,
                quality = quality,
                state = DownloadStates.FAILED,
                bytes = bytes,
                updated_at = System.currentTimeMillis(),
                duration_ms = track.durationMs,
            ),
        )
    }

    private suspend fun failQueuedTracks(
        trackDao: DownloadedTrackDao,
        tracks: List<TrackMetadata>,
        quality: String,
    ) {
        val now = System.currentTimeMillis()
        for (track in tracks) {
            val existing = trackDao.getByUri(track.uri)
            when (existing?.state) {
                DownloadStates.COMPLETED,
                DownloadStates.DOWNLOADING,
                DownloadStates.RESTARTING,
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
                    state = DownloadStates.FAILED,
                    bytes = existing?.bytes ?: 0,
                    updated_at = now,
                    duration_ms = track.durationMs.takeIf { it > 0 } ?: existing?.duration_ms ?: 0L,
                ),
            )
        }
    }

    private fun insufficientFreeSpace(context: Context): Boolean {
        val free = runCatching { context.filesDir.usableSpace }.getOrDefault(0L)
        return free in 1 until MIN_FREE_BYTES
    }

    private fun streamingQualityFromApi(value: String): StreamingQuality =
        when (value.uppercase()) {
            "LOW", "96" -> StreamingQuality.LOW
            "NORMAL", "160" -> StreamingQuality.NORMAL
            else -> StreamingQuality.HIGH
        }

    private fun kickService(app: Context) {
        ensureChannel(app)
        try {
            val intent = Intent(app, SpotifyDownloadService::class.java).apply {
                action = SpotifyDownloadService.ACTION_PROCESS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "kickService failed", e)
        }
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

    fun progressNotification(context: Context, content: String): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Downloading")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}

/**
 * Foreground service that drains the Spotify download queue one track at a time.
 */
class SpotifyDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_PROCESS
        SpotifyDownloadCenter.ensureChannel(this)
        val notification = SpotifyDownloadCenter.progressNotification(this, "Preparing…")
        ServiceCompat.startForeground(
            this,
            SpotifyDownloadCenter.foregroundNotificationId(),
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        scope.launch {
            try {
                if (action == ACTION_RESUME || action == ACTION_PROCESS) {
                    var more = true
                    while (more) {
                        more = SpotifyDownloadCenter.processNext(applicationContext)
                    }
                }
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_PROCESS = "com.lightphone.spotify.DOWNLOAD_PROCESS"
        const val ACTION_RESUME = "com.lightphone.spotify.DOWNLOAD_RESUME"
    }
}
