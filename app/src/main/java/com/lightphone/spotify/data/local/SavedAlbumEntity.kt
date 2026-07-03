package com.lightphone.spotify.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "saved_albums",
    indices = [Index(value = ["sort_index"])],
)
data class SavedAlbumEntity(
    @PrimaryKey val album_id: String,
    val uri: String,
    val name: String,
    val artist_names: String,
    val art_url: String?,
    val added_at: String?,
    val sort_index: Int,
)
