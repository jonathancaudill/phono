package com.lightphone.spotify.playback.tidal

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.lightphone.spotify.data.tidal.TidalApiClient
import com.lightphone.spotify.data.tidal.TidalAudioQuality
import com.lightphone.spotify.data.tidal.TidalUri
import java.io.File

/**
 * Builds playable [MediaItem]s from canonical `tidal:track:{id}` URIs.
 *
 * Call only from a background thread — hits `playbackinfopostpaywall`. ExoPlayer's
 * [androidx.media3.exoplayer.source.MediaSource.Factory.createMediaSource] runs on
 * the main looper; doing network there caused [android.os.NetworkOnMainThreadException].
 *
 * Clear DASH is written to a temp `.mpd` (high-tide / mopidy-tidal pattern) after
 * [TidalStreamResolve.sanitizeTidalMpd] strips illegal `group="main"`.
 */
@UnstableApi
object TidalPlayableItems {
    fun fromCanonicalUri(
        api: TidalApiClient,
        canonicalUri: String,
        quality: TidalAudioQuality,
        mpdCacheDir: File,
    ): MediaItem {
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
}
