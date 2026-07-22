package com.lightphone.spotify.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Album or playlist pinned for offline download. */
@Entity(tableName = "downloaded_collections")
data class DownloadedCollectionEntity(
    @PrimaryKey val uri: String,
    /** `album` or `playlist`. */
    val type: String,
    val name: String,
    val art_url: String?,
    val updated_at: Long,
)

@Entity(
    tableName = "downloaded_collection_tracks",
    primaryKeys = ["collection_uri", "track_uri"],
    foreignKeys = [
        ForeignKey(
            entity = DownloadedCollectionEntity::class,
            parentColumns = ["uri"],
            childColumns = ["collection_uri"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("track_uri")],
)
data class DownloadedCollectionTrackEntity(
    val collection_uri: String,
    val track_uri: String,
    val position: Int,
)

data class DownloadedCollectionWithProgress(
    val uri: String,
    val type: String,
    val name: String,
    val art_url: String?,
    val updated_at: Long,
    val track_count: Int,
    val completed_count: Int,
    val in_progress_count: Int,
    val failed_count: Int,
)

@Dao
interface DownloadedCollectionDao {
    @Query("SELECT * FROM downloaded_collections ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<DownloadedCollectionEntity>>

    @Query("SELECT * FROM downloaded_collections WHERE uri = :uri LIMIT 1")
    fun observeByUri(uri: String): Flow<DownloadedCollectionEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadedCollectionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembership(entity: DownloadedCollectionTrackEntity)

    @Query(
        """
        SELECT t.* FROM downloaded_tracks t
        INNER JOIN downloaded_collection_tracks m ON m.track_uri = t.uri
        WHERE m.collection_uri = :collectionUri
        ORDER BY m.position ASC
        """,
    )
    fun observeTracksForCollection(collectionUri: String): Flow<List<DownloadedTrackEntity>>

    @Query(
        """
        SELECT
          c.uri AS uri,
          c.type AS type,
          c.name AS name,
          c.art_url AS art_url,
          c.updated_at AS updated_at,
          COUNT(m.track_uri) AS track_count,
          SUM(CASE WHEN t.state = :completedState THEN 1 ELSE 0 END) AS completed_count,
          SUM(CASE WHEN t.state IN (:queuedState, :downloadingState, :restartingState) THEN 1 ELSE 0 END) AS in_progress_count,
          SUM(CASE WHEN t.state = :failedState THEN 1 ELSE 0 END) AS failed_count
        FROM downloaded_collections c
        LEFT JOIN downloaded_collection_tracks m ON m.collection_uri = c.uri
        LEFT JOIN downloaded_tracks t ON t.uri = m.track_uri
        GROUP BY c.uri
        ORDER BY c.updated_at DESC
        """,
    )
    fun observeCollectionsWithProgress(
        completedState: Int,
        queuedState: Int,
        downloadingState: Int,
        restartingState: Int,
        failedState: Int,
    ): Flow<List<DownloadedCollectionWithProgress>>

    @Query("DELETE FROM downloaded_collections WHERE uri = :uri")
    suspend fun deleteCollection(uri: String)

    @Query("DELETE FROM downloaded_collection_tracks WHERE collection_uri = :collectionUri AND track_uri = :trackUri")
    suspend fun deleteMembership(collectionUri: String, trackUri: String)

    @Query(
        """
        SELECT COUNT(*) FROM downloaded_collection_tracks
        WHERE track_uri = :trackUri
        """,
    )
    suspend fun membershipCountForTrack(trackUri: String): Int

    @Query("SELECT track_uri FROM downloaded_collection_tracks WHERE collection_uri = :collectionUri")
    suspend fun trackUrisForCollection(collectionUri: String): List<String>

    @Query("DELETE FROM downloaded_collections")
    suspend fun clearAll()
}
