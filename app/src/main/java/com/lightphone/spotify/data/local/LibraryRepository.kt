package com.lightphone.spotify.data.local

import androidx.room.withTransaction
import com.lightphone.spotify.data.SpotifyPlaylistSimple
import com.lightphone.spotify.data.SpotifySavedAlbum
import com.lightphone.spotify.data.TrackMetadata
import com.lightphone.spotify.data.mapWebApiError
import com.lightphone.spotify.data.webapi.SpotifyWebApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.Instant

class LibraryRepository(
    private val database: MonoDatabase,
    private val webApi: SpotifyWebApi,
) {
    private val trackDao = database.likedTrackDao()
    private val albumDao = database.savedAlbumDao()
    private val playlistDao = database.playlistDao()
    private val syncDao = database.librarySyncDao()

    private val likedTracksSync = LikedTracksSync(database, webApi)
    private val savedAlbumsSync = SavedAlbumsSync(database, webApi)
    private val userPlaylistsSync = UserPlaylistsSync(database, webApi)

    fun observeLikedTracks(): Flow<List<LikedTrackEntity>> = trackDao.observeAll()

    fun observeSavedAlbums(): Flow<List<SavedAlbumEntity>> = albumDao.observeAll()

    fun observePlaylists(): Flow<List<PlaylistEntity>> = playlistDao.observeAll()

    fun observeLikedTracksMeta(): Flow<LibrarySyncStateEntity?> =
        syncDao.observe(LibraryResource.LIKED_TRACKS)

    fun observeSavedAlbumsMeta(): Flow<LibrarySyncStateEntity?> =
        syncDao.observe(LibraryResource.SAVED_ALBUMS)

    fun observePlaylistsMeta(): Flow<LibrarySyncStateEntity?> =
        syncDao.observe(LibraryResource.USER_PLAYLISTS)

    /** Items + remote total + whether network fetch still has pages to pull. */
    fun likedTracksUiFlow(): Flow<Triple<List<LikedTrackEntity>, Int, Boolean>> =
        combine(observeLikedTracks(), observeLikedTracksMeta()) { items, sync ->
            val total = sync?.remote_total ?: items.size
            val hasMore = sync?.let { it.next_offset < it.remote_total } ?: false
            Triple(items, total, hasMore)
        }

    fun savedAlbumsUiFlow(): Flow<Triple<List<SavedAlbumEntity>, Int, Boolean>> =
        combine(observeSavedAlbums(), observeSavedAlbumsMeta()) { items, sync ->
            val total = sync?.remote_total ?: items.size
            val hasMore = sync?.let { it.next_offset < it.remote_total } ?: false
            Triple(items, total, hasMore)
        }

    fun playlistsUiFlow(): Flow<Triple<List<PlaylistEntity>, Int, Boolean>> =
        combine(observePlaylists(), observePlaylistsMeta()) { items, sync ->
            val total = sync?.remote_total ?: items.size
            val hasMore = sync?.let { it.next_offset < it.remote_total } ?: false
            Triple(items, total, hasMore)
        }

    suspend fun refreshLikedTracks(): Boolean {
        try {
            return likedTracksSync.refresh()
        } catch (e: Throwable) {
            throw Exception(mapWebApiError(e))
        }
    }

    suspend fun likedTracksNeedsFill(): Boolean {
        val sync = database.librarySyncDao().get(LibraryResource.LIKED_TRACKS) ?: return false
        return sync.next_offset < sync.remote_total
    }

    suspend fun appendLikedTracks(): Boolean {
        try {
            return likedTracksSync.append()
        } catch (e: Throwable) {
            throw Exception(mapWebApiError(e))
        }
    }

    suspend fun refreshSavedAlbums(): Boolean {
        try {
            return savedAlbumsSync.refresh()
        } catch (e: Throwable) {
            throw Exception(mapWebApiError(e))
        }
    }

    suspend fun savedAlbumsNeedsFill(): Boolean {
        val sync = database.librarySyncDao().get(LibraryResource.SAVED_ALBUMS) ?: return false
        return sync.next_offset < sync.remote_total
    }

    suspend fun appendSavedAlbums(): Boolean {
        try {
            return savedAlbumsSync.append()
        } catch (e: Throwable) {
            throw Exception(mapWebApiError(e))
        }
    }

    /** Drain all remaining liked-track pages into Room (parallel fetch batches). */
    suspend fun fillRemainingLikedTracks(): Int {
        try {
            return likedTracksSync.fillRemainingParallel()
        } catch (e: Throwable) {
            throw Exception(mapWebApiError(e))
        }
    }

    /** Drain all remaining saved-album pages into Room (parallel fetch batches). */
    suspend fun fillRemainingSavedAlbums(): Int {
        try {
            return savedAlbumsSync.fillRemainingParallel()
        } catch (e: Throwable) {
            throw Exception(mapWebApiError(e))
        }
    }

    suspend fun refreshPlaylists(): Boolean {
        try {
            return userPlaylistsSync.refresh()
        } catch (e: Throwable) {
            throw Exception(mapWebApiError(e))
        }
    }

    suspend fun playlistsNeedsFill(): Boolean {
        val sync = database.librarySyncDao().get(LibraryResource.USER_PLAYLISTS) ?: return false
        return sync.next_offset < sync.remote_total
    }

    suspend fun appendPlaylists(): Boolean {
        try {
            return userPlaylistsSync.append()
        } catch (e: Throwable) {
            throw Exception(mapWebApiError(e))
        }
    }

    /** Drain all remaining playlist pages into Room (parallel fetch batches). */
    suspend fun fillRemainingPlaylists(): Int {
        try {
            return userPlaylistsSync.fillRemainingParallel()
        } catch (e: Throwable) {
            throw Exception(mapWebApiError(e))
        }
    }

    suspend fun likedTracksForPlayback(fromIndex: Int, batchSize: Int = 500): List<TrackMetadata> {
        val tracks = trackDao.tracksFromOffset(fromIndex, batchSize)
        return tracks.map { it.toTrackMetadata() }
    }

    suspend fun playlistsSnapshot(): List<PlaylistEntity> =
        playlistDao.observeAll().first()

    suspend fun prependLikedTrack(metadata: TrackMetadata) {
        database.withTransaction {
            trackDao.shiftSortIndicesForPrepend()
            val sortIndex = trackDao.minSortIndex()?.minus(1) ?: 0
            trackDao.insertAll(
                listOf(
                    metadata.toLikedTrackEntity(
                        sortIndex = sortIndex,
                        addedAt = Instant.now().toString(),
                    ),
                ),
            )
            val sync = syncDao.get(LibraryResource.LIKED_TRACKS)
            if (sync != null) {
                syncDao.upsert(
                    sync.copy(
                        remote_total = sync.remote_total + 1,
                        head_added_at = Instant.now().toString(),
                        head_id = metadata.uri,
                        last_synced_at = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    suspend fun removeLikedTrack(uri: String) {
        database.withTransaction {
            trackDao.deleteByUri(uri)
            val sync = syncDao.get(LibraryResource.LIKED_TRACKS)
            if (sync != null && sync.remote_total > 0) {
                syncDao.upsert(
                    sync.copy(
                        remote_total = sync.remote_total - 1,
                        last_synced_at = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    suspend fun prependSavedAlbum(album: SpotifySavedAlbum) {
        database.withTransaction {
            albumDao.shiftSortIndicesForPrepend()
            val sortIndex = albumDao.minSortIndex()?.minus(1) ?: 0
            albumDao.insertAll(listOf(album.toEntity(sortIndex)))
            val sync = syncDao.get(LibraryResource.SAVED_ALBUMS)
            if (sync != null) {
                syncDao.upsert(
                    sync.copy(
                        remote_total = sync.remote_total + 1,
                        head_added_at = album.addedAt ?: Instant.now().toString(),
                        head_id = album.album!!.id,
                        last_synced_at = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    suspend fun removeSavedAlbum(albumId: String) {
        database.withTransaction {
            albumDao.deleteByAlbumId(albumId)
            val sync = syncDao.get(LibraryResource.SAVED_ALBUMS)
            if (sync != null && sync.remote_total > 0) {
                syncDao.upsert(
                    sync.copy(
                        remote_total = sync.remote_total - 1,
                        last_synced_at = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    suspend fun prependPlaylist(playlist: SpotifyPlaylistSimple) {
        database.withTransaction {
            playlistDao.shiftSortIndicesForPrepend()
            val sortIndex = playlistDao.minSortIndex()?.minus(1) ?: 0
            playlistDao.insertAll(listOf(playlist.toEntity(sortIndex)))
            val sync = syncDao.get(LibraryResource.USER_PLAYLISTS)
            if (sync != null) {
                syncDao.upsert(
                    sync.copy(
                        remote_total = sync.remote_total + 1,
                        head_added_at = playlist.snapshotId,
                        head_id = playlist.id,
                        last_synced_at = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    suspend fun removePlaylist(playlistId: String) {
        database.withTransaction {
            playlistDao.deleteByPlaylistId(playlistId)
            val sync = syncDao.get(LibraryResource.USER_PLAYLISTS)
            if (sync != null && sync.remote_total > 0) {
                syncDao.upsert(
                    sync.copy(
                        remote_total = sync.remote_total - 1,
                        last_synced_at = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    suspend fun clearAll() {
        database.withTransaction {
            trackDao.clearAll()
            albumDao.clearAll()
            playlistDao.clearAll()
            syncDao.delete(LibraryResource.LIKED_TRACKS)
            syncDao.delete(LibraryResource.SAVED_ALBUMS)
            syncDao.delete(LibraryResource.USER_PLAYLISTS)
        }
    }
}
