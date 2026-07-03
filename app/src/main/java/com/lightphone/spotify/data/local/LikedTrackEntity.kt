package com.lightphone.spotify.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "liked_tracks",
    indices = [Index(value = ["sort_index"])],
)
data class LikedTrackEntity(
    @PrimaryKey val uri: String,
    val title: String,
    val artists: String,
    val album_name: String,
    val duration_ms: Long,
    val art_url: String?,
    val album_id: String?,
    val added_at: String?,
    val sort_index: Int,
)
