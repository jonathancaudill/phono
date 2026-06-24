package com.lightphone.spotify.data.local

import com.lightphone.spotify.data.SpotifySavedAlbum
import com.lightphone.spotify.data.SpotifySavedTrack
import com.lightphone.spotify.data.TrackMetadata
import com.lightphone.spotify.data.toMetadata

fun SpotifySavedTrack.toEntity(sortIndex: Int): LikedTrackEntity {
    val meta = track!!.toMetadata()
    return LikedTrackEntity(
        uri = meta.uri,
        title = meta.title,
        artists = meta.artists,
        album_name = meta.album,
        duration_ms = meta.durationMs,
        art_url = meta.artUrl,
        album_id = meta.albumId,
        added_at = addedAt,
        sort_index = sortIndex,
    )
}

fun SpotifySavedAlbum.toEntity(sortIndex: Int): SavedAlbumEntity {
    val album = album!!
    return SavedAlbumEntity(
        album_id = album.id,
        uri = album.uri.ifBlank { "spotify:album:${album.id}" },
        name = album.name,
        artist_names = album.artists.joinToString(" · ") { it.name },
        art_url = album.images.firstOrNull()?.url,
        added_at = addedAt,
        sort_index = sortIndex,
    )
}

fun TrackMetadata.toLikedTrackEntity(sortIndex: Int, addedAt: String? = null): LikedTrackEntity =
    LikedTrackEntity(
        uri = uri,
        title = title,
        artists = artists,
        album_name = album,
        duration_ms = durationMs,
        art_url = artUrl,
        album_id = albumId,
        added_at = addedAt,
        sort_index = sortIndex,
    )
