package com.lightphone.spotify.playback.tidal

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import com.lightphone.spotify.data.local.PhonoDatabase
import com.lightphone.spotify.data.tidal.TidalApiClient
import com.lightphone.spotify.data.tidal.TidalAudioQuality
import com.lightphone.spotify.data.tidal.TidalUri
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Builds playable [MediaItem]s from canonical `tidal:track:{id}` URIs.
 *
 * Call only from a background thread — hits `playbackinfopostpaywall` when not
 * already downloaded. Clear DASH is written to a temp `.mpd` after sanitize.
 */
@UnstableApi
object TidalPlayableItems {
    fun fromCanonicalUri(
        context: Context,
        api: TidalApiClient,
        canonicalUri: String,
        quality: TidalAudioQuality,
        mpdCacheDir: File,
    ): MediaItem {
        tryOfflineMediaItem(context, canonicalUri)?.let { return it }

        val id = TidalUri.rawId(canonicalUri)
        return when (val resolved = TidalStreamResolve.resolve(api, id, quality.apiValue)) {
            is TidalResolvedStream.Progressive -> MediaItem.Builder()
                .setMediaId(canonicalUri)
                .setUri(Uri.parse(resolved.url))
                .setCustomCacheKey(resolved.cacheKey)
                .build()
            is TidalResolvedStream.ClearDash -> {
                mpdCacheDir.mkdirs()
                val file = File(mpdCacheDir, "${id}_${resolved.audioQuality}.mpd")
                file.writeText(resolved.mpdXml)
                MediaItem.Builder()
                    .setMediaId(canonicalUri)
                    .setUri(Uri.fromFile(file))
                    .setMimeType(MimeTypes.APPLICATION_MPD)
                    .setCustomCacheKey(resolved.cacheKey)
                    .build()
            }
        }
    }

    /** Completed Media3 download for [canonicalUri], or null if not pinned. */
    fun tryOfflineMediaItem(context: Context, canonicalUri: String): MediaItem? =
        offlineMediaItem(context, canonicalUri)

    /** Prefer a completed Media3 download so offline play needs no playbackinfo. */
    private fun offlineMediaItem(context: Context, canonicalUri: String): MediaItem? {
        val row = runBlocking {
            PhonoDatabase.get(context).downloadedTrackDao().getByUri(canonicalUri)
        } ?: return null
        if (row.state != Download.STATE_COMPLETED) return null
        val id = TidalUri.rawId(canonicalUri)
        val downloadId = "tidal:$id:${row.quality}"
        val download = runCatching {
            TidalDownloadCenter.downloadManager(context).downloadIndex.getDownload(downloadId)
        }.getOrNull() ?: return null
        if (download.state != Download.STATE_COMPLETED) return null
        // Media3 recommends DownloadRequest.toMediaItem() so mime/streamKeys/cache key match
        // the completed download (DASH must not invent a customCacheKey).
        return download.request.toMediaItem(
            MediaItem.Builder().setMediaId(canonicalUri),
        )
    }
}
