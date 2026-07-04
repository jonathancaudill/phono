package com.lightphone.spotify.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AlbumDetailDao {
    @Query("SELECT * FROM album_details WHERE album_id = :albumId LIMIT 1")
    suspend fun get(albumId: String): AlbumDetailEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AlbumDetailEntity)

    @Query("DELETE FROM album_details WHERE album_id = :albumId")
    suspend fun delete(albumId: String)

    @Query("DELETE FROM album_details")
    suspend fun clearAll()
}

@Dao
interface PlaylistDetailDao {
    @Query("SELECT * FROM playlist_details WHERE playlist_id = :playlistId LIMIT 1")
    suspend fun get(playlistId: String): PlaylistDetailEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaylistDetailEntity)

    @Query("DELETE FROM playlist_details WHERE playlist_id = :playlistId")
    suspend fun delete(playlistId: String)

    @Query("DELETE FROM playlist_details")
    suspend fun clearAll()
}

@Dao
interface PlaylistTrackUriDao {
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM playlist_track_uris
            WHERE playlist_id = :playlistId AND track_uri = :trackUri
            LIMIT 1
        )
        """,
    )
    suspend fun contains(playlistId: String, trackUri: String): Boolean

    @Query("SELECT playlist_id FROM playlist_track_uris WHERE track_uri = :trackUri")
    suspend fun playlistIdsContaining(trackUri: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: PlaylistTrackUriEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<PlaylistTrackUriEntity>)

    @Query("DELETE FROM playlist_track_uris WHERE playlist_id = :playlistId AND track_uri = :trackUri")
    suspend fun delete(playlistId: String, trackUri: String)

    @Query("DELETE FROM playlist_track_uris WHERE playlist_id = :playlistId")
    suspend fun deleteForPlaylist(playlistId: String)

    @Query("DELETE FROM playlist_track_uris")
    suspend fun clearAll()
}

@Dao
interface PlaylistUriIndexDao {
    @Query("SELECT * FROM playlist_uri_index WHERE playlist_id = :playlistId LIMIT 1")
    suspend fun get(playlistId: String): PlaylistUriIndexEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaylistUriIndexEntity)

    @Query("DELETE FROM playlist_uri_index WHERE playlist_id = :playlistId")
    suspend fun delete(playlistId: String)

    @Query("DELETE FROM playlist_uri_index")
    suspend fun clearAll()
}
