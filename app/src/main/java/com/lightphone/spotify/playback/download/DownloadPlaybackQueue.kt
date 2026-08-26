package com.lightphone.spotify.playback.download

import com.lightphone.spotify.data.TrackMetadata
import com.lightphone.spotify.data.local.DownloadedTrackEntity

data class DownloadPlayStart(
    val tracks: List<TrackMetadata>,
    val startIndex: Int,
)

/**
 * Builds a playback context from a downloaded album/playlist.
 *
 * Completed pins only, in collection order. In-progress or failed rows cannot
 * play offline; omitting them keeps auto-advance, shuffle, and repeat on the
 * pins that actually exist.
 */
object DownloadPlaybackQueue {
    fun fromCollection(
        tracks: List<DownloadedTrackEntity>,
        startUri: String,
    ): DownloadPlayStart? {
        val playable = tracks
            .filter { it.state == DownloadStates.COMPLETED }
            .map { it.toTrackMetadata() }
        val startIndex = playable.indexOfFirst { it.uri == startUri }
        if (startIndex < 0) return null
        return DownloadPlayStart(playable, startIndex)
    }
}

fun DownloadedTrackEntity.toTrackMetadata(): TrackMetadata = TrackMetadata(
    uri = uri,
    title = title,
    artists = artists,
    album = album,
    durationMs = duration_ms,
    artUrl = art_url,
)
