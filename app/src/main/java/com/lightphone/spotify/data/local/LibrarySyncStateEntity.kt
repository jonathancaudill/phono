package com.lightphone.spotify.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "library_sync_state")
data class LibrarySyncStateEntity(
    @PrimaryKey val resource: LibraryResource,
    val remote_total: Int,
    val head_added_at: String?,
    val head_id: String?,
    val next_offset: Int,
    val last_synced_at: Long,
)
