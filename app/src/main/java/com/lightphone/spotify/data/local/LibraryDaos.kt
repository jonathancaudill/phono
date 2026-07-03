package com.lightphone.spotify.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lightphone.spotify.data.TrackMetadata
import kotlinx.coroutines.flow.Flow

@Dao
interface LikedTrackDao {
    @Query("SELECT * FROM liked_tracks ORDER BY sort_index ASC")
    fun observeAll(): Flow<List<LikedTrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<LikedTrackEntity>)

    @Query("DELETE FROM liked_tracks")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM liked_tracks")
    suspend fun count(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM liked_tracks WHERE uri = :uri LIMIT 1)")
    suspend fun exists(uri: String): Boolean

    @Query("SELECT * FROM liked_tracks ORDER BY sort_index ASC LIMIT :limit OFFSET :offset")
    suspend fun tracksFromOffset(offset: Int, limit: Int): List<LikedTrackEntity>

    @Query("DELETE FROM liked_tracks WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)

    @Query(
        """
        UPDATE liked_tracks SET sort_index = sort_index + 1
        """,
    )
    suspend fun shiftSortIndicesForPrepend()

    @Query("SELECT MIN(sort_index) FROM liked_tracks")
    suspend fun minSortIndex(): Int?
}

@Dao
interface SavedAlbumDao {
    @Query(
        """
        SELECT * FROM saved_albums
        ORDER BY CASE WHEN added_at IS NULL THEN 1 ELSE 0 END ASC,
                 added_at DESC,
                 sort_index ASC
        """,
    )
    fun observeAll(): Flow<List<SavedAlbumEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SavedAlbumEntity>)

    @Query("DELETE FROM saved_albums")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM saved_albums")
    suspend fun count(): Int

    @Query("DELETE FROM saved_albums WHERE album_id = :albumId")
    suspend fun deleteByAlbumId(albumId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM saved_albums WHERE album_id = :albumId LIMIT 1)")
    suspend fun exists(albumId: String): Boolean

    @Query(
        """
        UPDATE saved_albums SET sort_index = sort_index + 1
        """,
    )
    suspend fun shiftSortIndicesForPrepend()

    @Query("SELECT MIN(sort_index) FROM saved_albums")
    suspend fun minSortIndex(): Int?
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY sort_index ASC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<PlaylistEntity>)

    @Query("DELETE FROM playlists")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM playlists")
    suspend fun count(): Int

    @Query("DELETE FROM playlists WHERE playlist_id = :playlistId")
    suspend fun deleteByPlaylistId(playlistId: String)

    @Query("UPDATE playlists SET sort_index = sort_index + 1")
    suspend fun shiftSortIndicesForPrepend()

    @Query("SELECT MIN(sort_index) FROM playlists")
    suspend fun minSortIndex(): Int?
}

@Dao
interface LibrarySyncDao {
    @Query("SELECT * FROM library_sync_state WHERE resource = :resource LIMIT 1")
    suspend fun get(resource: LibraryResource): LibrarySyncStateEntity?

    @Query("SELECT * FROM library_sync_state WHERE resource = :resource LIMIT 1")
    fun observe(resource: LibraryResource): Flow<LibrarySyncStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: LibrarySyncStateEntity)

    @Query("DELETE FROM library_sync_state WHERE resource = :resource")
    suspend fun delete(resource: LibraryResource)
}

fun LikedTrackEntity.toTrackMetadata(): TrackMetadata = TrackMetadata(
    uri = uri,
    title = title,
    artists = artists,
    album = album_name,
    durationMs = duration_ms,
    artUrl = art_url,
    albumId = album_id,
)

fun SavedAlbumEntity.toSpotifySavedAlbum(): com.lightphone.spotify.data.SpotifySavedAlbum {
    val album = com.lightphone.spotify.data.SpotifyAlbumSimple(
        id = album_id,
        name = name,
        uri = uri,
        images = art_url?.let {
            listOf(com.lightphone.spotify.data.SpotifyImage(url = it))
        } ?: emptyList(),
        artists = artist_names.split(" · ").filter { it.isNotBlank() }.map { name ->
            com.lightphone.spotify.data.SpotifyArtist(name = name)
        },
    )
    return com.lightphone.spotify.data.SpotifySavedAlbum(
        addedAt = added_at,
        album = album,
    )
}
