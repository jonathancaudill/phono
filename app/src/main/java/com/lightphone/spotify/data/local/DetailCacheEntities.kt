package com.lightphone.spotify.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "album_details")
data class AlbumDetailEntity(
    @PrimaryKey val album_id: String,
    val detail_json: String,
    val is_saved: Boolean,
    val fetched_at: Long,
)

@Entity(tableName = "playlist_details")
data class PlaylistDetailEntity(
    @PrimaryKey val playlist_id: String,
    val detail_json: String,
    val tracks_json: String,
    val snapshot_id: String?,
    val fetched_at: Long,
)

@Entity(
    tableName = "playlist_track_uris",
    primaryKeys = ["playlist_id", "track_uri"],
)
data class PlaylistTrackUriEntity(
    val playlist_id: String,
    val track_uri: String,
)
