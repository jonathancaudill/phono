package com.lightphone.spotify.playback.download

import android.content.Context
import com.lightphone.spotify.data.TrackMetadata

/**
 * Backend-neutral offline download façade. TIDAL uses Media3; Spotify uses a
 * custom Rust decrypt-to-Ogg pipeline. UI and [AppViewModel] talk only to this.
 */
interface OfflineDownloadCenter {
    val supported: Boolean

    /** Restart unfinished downloads after process death (cold start). */
    fun resumeDownloads(context: Context)

    /** Pin a single track for offline playback. */
    fun download(context: Context, track: TrackMetadata, quality: String)

    fun downloadCollection(
        context: Context,
        collectionUri: String,
        type: String,
        name: String,
        artUrl: String?,
        tracks: List<TrackMetadata>,
        quality: String,
    )

    fun remove(context: Context, track: TrackMetadata, quality: String)

    fun removeCollection(context: Context, collectionUri: String)
}

/** No-op center when the active backend does not support offline downloads. */
object NoOpOfflineDownloadCenter : OfflineDownloadCenter {
    override val supported: Boolean = false
    override fun resumeDownloads(context: Context) = Unit
    override fun download(context: Context, track: TrackMetadata, quality: String) = Unit
    override fun downloadCollection(
        context: Context,
        collectionUri: String,
        type: String,
        name: String,
        artUrl: String?,
        tracks: List<TrackMetadata>,
        quality: String,
    ) = Unit
    override fun remove(context: Context, track: TrackMetadata, quality: String) = Unit
    override fun removeCollection(context: Context, collectionUri: String) = Unit
}
