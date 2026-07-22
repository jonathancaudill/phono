package com.lightphone.spotify.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Index of TIDAL tracks pinned for offline playback (Media3 `DownloadManager`
 * writes the audio into the shared no-evictor cache; this row drives the UI and
 * storage totals). `uri` is the canonical `tidal:track:{id}` id.
 */
@Entity(tableName = "downloaded_tracks")
data class DownloadedTrackEntity(
    @PrimaryKey val uri: String,
    val title: String,
    val artists: String,
    val album: String,
    val art_url: String?,
    val quality: String,
    /** Media3 Download.STATE_* value. */
    val state: Int,
    val bytes: Long,
    val updated_at: Long,
)

@Dao
interface DownloadedTrackDao {
    @Query("SELECT * FROM downloaded_tracks ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<DownloadedTrackEntity>>

    @Query("SELECT * FROM downloaded_tracks ORDER BY updated_at DESC")
    suspend fun getAll(): List<DownloadedTrackEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM downloaded_tracks WHERE uri = :uri AND state = :completedState LIMIT 1)")
    suspend fun isDownloaded(uri: String, completedState: Int): Boolean

    @Query("SELECT * FROM downloaded_tracks WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): DownloadedTrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadedTrackEntity)

    @Query("UPDATE downloaded_tracks SET state = :state, bytes = :bytes, updated_at = :updatedAt WHERE uri = :uri")
    suspend fun updateState(uri: String, state: Int, bytes: Long, updatedAt: Long)

    @Query("DELETE FROM downloaded_tracks WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("DELETE FROM downloaded_tracks")
    suspend fun clearAll()

    @Query("SELECT COALESCE(SUM(bytes), 0) FROM downloaded_tracks")
    suspend fun totalBytes(): Long
}
