package com.lightphone.spotify.playback.tidal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.lightphone.spotify.data.TrackMetadata
import com.lightphone.spotify.data.local.DownloadedTrackEntity
import com.lightphone.spotify.data.local.PhonoDatabase
import com.lightphone.spotify.data.tidal.TidalApiClient
import com.lightphone.spotify.data.tidal.TidalAuth
import com.lightphone.spotify.data.tidal.TidalUri
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Optional TIDAL offline playback (~20-30 users; intentionally simple).
 *
 * Downloads write into the shared [TidalMediaCache] (no-evictor) at download time,
 * when the signed URL is still valid, keyed by `tidal:{id}:{quality}` — the same
 * key streaming uses, so a downloaded track plays back offline with no network.
 *
 * **Caveat:** this works only while TIDAL serves clear FLAC. If TIDAL migrates
 * these streams to Widevine, offline would require persistable DRM licenses (a much
 * bigger project) — same fragility class as the client-id rotation tax.
 */
@UnstableApi
object TidalDownloadCenter {
    private const val NOTIFICATION_CHANNEL_ID = "phono_downloads"
    private const val NOTIFICATION_ID = 0x70646c00 // "pdl"
    private const val COMPLETED = Download.STATE_COMPLETED

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var manager: DownloadManager? = null

    @Volatile
    private var notificationHelper: DownloadNotificationHelper? = null

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
        val auth = TidalAuth(appContext)
        val api = TidalApiClient(auth)
        val dm = DownloadManager(
            appContext,
            TidalMediaCache.databaseProvider(appContext),
            TidalMediaCache.cache(appContext),
            TidalMediaCache.resolvingUpstreamFactory(api),
            Executors.newFixedThreadPool(2),
        )
        val db = PhonoDatabase.get(appContext)
        dm.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?,
            ) {
                val uri = canonicalUri(download.request.id) ?: return
                scope.launch {
                    db.downloadedTrackDao().updateState(
                        uri = uri,
                        state = download.state,
                        bytes = download.bytesDownloaded,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                val uri = canonicalUri(download.request.id) ?: return
                scope.launch { db.downloadedTrackDao().delete(uri) }
            }
        })
        return dm
    }

    /** Pin [track] for offline playback at [quality] (a TIDAL audioquality tier). */
    fun download(context: Context, track: TrackMetadata, quality: String) {
        val appContext = context.applicationContext
        val id = TidalUri.rawId(track.uri)
        val cacheKey = "tidal:$id:$quality"
        val request = DownloadRequest.Builder(cacheKey, Uri.parse("${TidalMediaCache.STREAM_SCHEME}://$id?q=$quality"))
            .build()
        scope.launch {
            PhonoDatabase.get(appContext).downloadedTrackDao().upsert(
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
        DownloadService.sendAddDownload(appContext, TidalDownloadService::class.java, request, false)
    }

    fun remove(context: Context, track: TrackMetadata, quality: String) {
        val id = TidalUri.rawId(track.uri)
        DownloadService.sendRemoveDownload(
            context.applicationContext,
            TidalDownloadService::class.java,
            "tidal:$id:$quality",
            false,
        )
    }

    /** `tidal:{id}:{quality}` download id -> canonical `tidal:track:{id}` uri. */
    private fun canonicalUri(downloadId: String): String? {
        val parts = downloadId.split(":")
        if (parts.size < 2) return null
        return "tidal:track:${parts[1]}"
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
