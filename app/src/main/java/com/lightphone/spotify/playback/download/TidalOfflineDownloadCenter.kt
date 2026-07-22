package com.lightphone.spotify.playback.download

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.lightphone.spotify.data.TrackMetadata
import com.lightphone.spotify.data.tidal.TidalApiClient
import com.lightphone.spotify.data.tidal.TidalAuth
import com.lightphone.spotify.playback.tidal.TidalDownloadCenter

/**
 * TIDAL Media3 adapter — keeps [TidalDownloadCenter] internals in `playback.tidal`
 * while exposing the shared [OfflineDownloadCenter] surface.
 */
@UnstableApi
class TidalOfflineDownloadCenter(
    private val auth: TidalAuth,
    private val api: TidalApiClient,
) : OfflineDownloadCenter {
    override val supported: Boolean = true

    init {
        TidalDownloadCenter.bind(auth, api)
    }

    override fun resumeDownloads(context: Context) {
        TidalDownloadCenter.resumeDownloads(context)
    }

    override fun download(context: Context, track: TrackMetadata, quality: String) {
        TidalDownloadCenter.download(context, track, quality)
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
        TidalDownloadCenter.downloadCollection(
            context, collectionUri, type, name, artUrl, tracks, quality,
        )
    }

    override fun remove(context: Context, track: TrackMetadata, quality: String) {
        TidalDownloadCenter.remove(context, track, quality)
    }

    override fun removeCollection(context: Context, collectionUri: String) {
        TidalDownloadCenter.removeCollection(context, collectionUri)
    }
}
