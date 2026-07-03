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
}

@Dao
interface PlaylistDetailDao {
    @Query("SELECT * FROM playlist_details WHERE playlist_id = :playlistId LIMIT 1")
    suspend fun get(playlistId: String): PlaylistDetailEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaylistDetailEntity)

    @Query("DELETE FROM playlist_details WHERE playlist_id = :playlistId")
    suspend fun delete(playlistId: String)
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<PlaylistTrackUriEntity>)

    @Query("DELETE FROM playlist_track_uris WHERE playlist_id = :playlistId")
    suspend fun deleteForPlaylist(playlistId: String)
}
