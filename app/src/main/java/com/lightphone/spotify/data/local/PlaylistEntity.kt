package com.lightphone.spotify.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "playlists",
    indices = [Index(value = ["sort_index"])],
)
data class PlaylistEntity(
    @PrimaryKey val playlist_id: String,
    val uri: String,
    val name: String,
    val owner_id: String,
    val owner_name: String,
    val art_url: String?,
    val track_count: Int,
    val snapshot_id: String?,
    val is_public: Boolean,
    val is_collaborative: Boolean,
    val sort_index: Int,
)
