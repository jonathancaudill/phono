package com.lightphone.spotify.data.local

import androidx.room.withTransaction
import com.lightphone.spotify.data.SpotifySavedAlbum
import com.lightphone.spotify.data.SpotifySavedTrack
import com.lightphone.spotify.data.SpotifyPlaylistSimple
import com.lightphone.spotify.data.webapi.LibraryPage
import com.lightphone.spotify.data.webapi.SpotifyWebApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Network fetch + Room persistence for liked tracks and saved albums.
 *
 * [refresh] performs a head-check delta: skips the full re-download when the
 * library head and local row count already match Spotify's total.
 *
 * [fillRemainingParallel] drains every remaining page (parallel fetch batches) so
 * the local cache becomes a complete offline copy of the library.
 */
internal class LikedTracksSync(
    private val database: MonoDatabase,
    private val webApi: SpotifyWebApi,
) {
    private val trackDao = database.likedTrackDao()
    private val syncDao = database.librarySyncDao()

    /** @return true when a network fetch and local rewrite occurred. */
    suspend fun refresh(): Boolean {
        val page = webApi.savedTracksPage(offset = 0)
        val head = page.items.firstOrNull()
        val sync = syncDao.get(LibraryResource.LIKED_TRACKS)

        if (sync != null &&
            sync.remote_total == page.total &&
            sync.head_added_at == head?.addedAt &&
            sync.head_id == head?.track?.uri &&
            sync.next_offset >= sync.remote_total
        ) {
            return false
        }

        database.withTransaction {
            trackDao.clearAll()
            syncDao.delete(LibraryResource.LIKED_TRACKS)
            insertPage(page, startSortIndex = 0)
            updateSyncState(page, nextOffset = page.items.size, isRefresh = true)
        }
        return true
    }

    suspend fun append(): Boolean {
        val sync = syncDao.get(LibraryResource.LIKED_TRACKS) ?: return false
        val offset = sync.next_offset
        if (offset >= sync.remote_total) return false

        val page = webApi.savedTracksPage(offset = offset)
        if (page.items.isEmpty()) return false

        database.withTransaction {
            insertPage(page, startSortIndex = offset)
            updateSyncState(page, nextOffset = offset + page.items.size, isRefresh = false)
        }
        return offset + page.items.size < page.total
    }

    /** Fetch every remaining page into Room. Returns rows inserted. */
    suspend fun fillRemainingParallel(pageParallelism: Int = 4): Int {
        var inserted = 0
        while (true) {
            val sync = syncDao.get(LibraryResource.LIKED_TRACKS) ?: break
            if (sync.next_offset >= sync.remote_total) break

            val batch = pendingOffsets(sync, pageParallelism)
            if (batch.isEmpty()) break

            val pages = coroutineScope {
                batch.map { offset ->
                    async { offset to webApi.savedTracksPage(offset) }
                }.awaitAll()
            }

            database.withTransaction {
                pages.sortedBy { it.first }.forEach { (offset, page) ->
                    if (page.items.isNotEmpty()) {
                        insertPage(page, startSortIndex = offset)
                        inserted += page.items.size
                    }
                }
                if (pages.isEmpty()) return@withTransaction
                val last = pages.maxBy { it.first }
                val nextOffset = last.first + last.second.items.size
                updateSyncState(last.second, nextOffset = nextOffset, isRefresh = false)
            }
        }
        return inserted
    }

    private fun pendingOffsets(sync: LibrarySyncStateEntity, count: Int): List<Int> {
        val pageSize = SpotifyWebApi.LIBRARY_PAGE_LIMIT
        val offsets = mutableListOf<Int>()
        var offset = sync.next_offset
        while (offset < sync.remote_total && offsets.size < count) {
            offsets.add(offset)
            offset += pageSize
        }
        return offsets
    }

    private suspend fun insertPage(page: LibraryPage<SpotifySavedTrack>, startSortIndex: Int) {
        val entities = page.items.mapIndexed { index, saved ->
            saved.toEntity(sortIndex = startSortIndex + index)
        }
        if (entities.isNotEmpty()) {
            trackDao.insertAll(entities)
        }
    }

    private suspend fun updateSyncState(
        page: LibraryPage<SpotifySavedTrack>,
        nextOffset: Int,
        isRefresh: Boolean,
    ) {
        val existing = syncDao.get(LibraryResource.LIKED_TRACKS)
        val head = page.items.firstOrNull()
        syncDao.upsert(
            LibrarySyncStateEntity(
                resource = LibraryResource.LIKED_TRACKS,
                remote_total = page.total,
                head_added_at = if (isRefresh) head?.addedAt else existing?.head_added_at,
                head_id = if (isRefresh) head?.track?.uri else existing?.head_id,
                next_offset = nextOffset,
                last_synced_at = System.currentTimeMillis(),
            ),
        )
    }
}

internal class SavedAlbumsSync(
    private val database: MonoDatabase,
    private val webApi: SpotifyWebApi,
) {
    private val albumDao = database.savedAlbumDao()
    private val syncDao = database.librarySyncDao()

    suspend fun refresh(): Boolean {
        val page = webApi.savedAlbumsPage(offset = 0)
        val head = page.items.firstOrNull()
        val sync = syncDao.get(LibraryResource.SAVED_ALBUMS)

        if (sync != null &&
            sync.remote_total == page.total &&
            sync.head_added_at == head?.addedAt &&
            sync.head_id == head?.album?.id &&
            sync.next_offset >= sync.remote_total
        ) {
            return false
        }

        database.withTransaction {
            albumDao.clearAll()
            syncDao.delete(LibraryResource.SAVED_ALBUMS)
            insertPage(page, startSortIndex = 0)
            updateSyncState(page, nextOffset = page.items.size, isRefresh = true)
        }
        return true
    }

    suspend fun append(): Boolean {
        val sync = syncDao.get(LibraryResource.SAVED_ALBUMS) ?: return false
        val offset = sync.next_offset
        if (offset >= sync.remote_total) return false

        val page = webApi.savedAlbumsPage(offset = offset)
        if (page.items.isEmpty()) return false

        database.withTransaction {
            insertPage(page, startSortIndex = offset)
            updateSyncState(page, nextOffset = offset + page.items.size, isRefresh = false)
        }
        return offset + page.items.size < page.total
    }

    suspend fun fillRemainingParallel(pageParallelism: Int = 4): Int {
        var inserted = 0
        while (true) {
            val sync = syncDao.get(LibraryResource.SAVED_ALBUMS) ?: break
            if (sync.next_offset >= sync.remote_total) break

            val batch = pendingOffsets(sync, pageParallelism)
            if (batch.isEmpty()) break

            val pages = coroutineScope {
                batch.map { offset ->
                    async { offset to webApi.savedAlbumsPage(offset) }
                }.awaitAll()
            }

            database.withTransaction {
                pages.sortedBy { it.first }.forEach { (offset, page) ->
                    if (page.items.isNotEmpty()) {
                        insertPage(page, startSortIndex = offset)
                        inserted += page.items.size
                    }
                }
                if (pages.isEmpty()) return@withTransaction
                val last = pages.maxBy { it.first }
                val nextOffset = last.first + last.second.items.size
                updateSyncState(last.second, nextOffset = nextOffset, isRefresh = false)
            }
        }
        return inserted
    }

    private fun pendingOffsets(sync: LibrarySyncStateEntity, count: Int): List<Int> {
        val pageSize = SpotifyWebApi.LIBRARY_PAGE_LIMIT
        val offsets = mutableListOf<Int>()
        var offset = sync.next_offset
        while (offset < sync.remote_total && offsets.size < count) {
            offsets.add(offset)
            offset += pageSize
        }
        return offsets
    }

    private suspend fun insertPage(page: LibraryPage<SpotifySavedAlbum>, startSortIndex: Int) {
        val entities = page.items.mapIndexed { index, saved ->
            saved.toEntity(sortIndex = startSortIndex + index)
        }
        if (entities.isNotEmpty()) {
            albumDao.insertAll(entities)
        }
    }

    private suspend fun updateSyncState(
        page: LibraryPage<SpotifySavedAlbum>,
        nextOffset: Int,
        isRefresh: Boolean,
    ) {
        val existing = syncDao.get(LibraryResource.SAVED_ALBUMS)
        val head = page.items.firstOrNull()
        syncDao.upsert(
            LibrarySyncStateEntity(
                resource = LibraryResource.SAVED_ALBUMS,
                remote_total = page.total,
                head_added_at = if (isRefresh) head?.addedAt else existing?.head_added_at,
                head_id = if (isRefresh) head?.album?.id else existing?.head_id,
                next_offset = nextOffset,
                last_synced_at = System.currentTimeMillis(),
            ),
        )
    }
}

internal class UserPlaylistsSync(
    private val database: MonoDatabase,
    private val webApi: SpotifyWebApi,
) {
    // sort_index follows GET /me/playlists page order (Spotify library display order;
    // pinned playlists appear first in the app and are preserved at low sort_index values).
    private val playlistDao = database.playlistDao()
    private val syncDao = database.librarySyncDao()

    suspend fun refresh(): Boolean {
        val page = webApi.savedPlaylistsPage(offset = 0)
        val head = page.items.firstOrNull()
        val sync = syncDao.get(LibraryResource.USER_PLAYLISTS)

        if (sync != null &&
            sync.remote_total == page.total &&
            sync.head_id == head?.id &&
            sync.head_added_at == head?.snapshotId &&
            sync.next_offset >= sync.remote_total
        ) {
            return false
        }

        database.withTransaction {
            playlistDao.clearAll()
            syncDao.delete(LibraryResource.USER_PLAYLISTS)
            insertPage(page, startSortIndex = 0)
            updateSyncState(page, nextOffset = page.items.size, isRefresh = true)
        }
        return true
    }

    suspend fun append(): Boolean {
        val sync = syncDao.get(LibraryResource.USER_PLAYLISTS) ?: return false
        val offset = sync.next_offset
        if (offset >= sync.remote_total) return false

        val page = webApi.savedPlaylistsPage(offset = offset)
        if (page.items.isEmpty()) return false

        database.withTransaction {
            insertPage(page, startSortIndex = offset)
            updateSyncState(page, nextOffset = offset + page.items.size, isRefresh = false)
        }
        return offset + page.items.size < page.total
    }

    suspend fun fillRemainingParallel(pageParallelism: Int = 4): Int {
        var inserted = 0
        while (true) {
            val sync = syncDao.get(LibraryResource.USER_PLAYLISTS) ?: break
            if (sync.next_offset >= sync.remote_total) break

            val batch = pendingOffsets(sync, pageParallelism)
            if (batch.isEmpty()) break

            val pages = coroutineScope {
                batch.map { offset ->
                    async { offset to webApi.savedPlaylistsPage(offset) }
                }.awaitAll()
            }

            database.withTransaction {
                pages.sortedBy { it.first }.forEach { (offset, page) ->
                    if (page.items.isNotEmpty()) {
                        insertPage(page, startSortIndex = offset)
                        inserted += page.items.size
                    }
                }
                if (pages.isEmpty()) return@withTransaction
                val last = pages.maxBy { it.first }
                val nextOffset = last.first + last.second.items.size
                updateSyncState(last.second, nextOffset = nextOffset, isRefresh = false)
            }
        }
        return inserted
    }

    private fun pendingOffsets(sync: LibrarySyncStateEntity, count: Int): List<Int> {
        val pageSize = SpotifyWebApi.LIBRARY_PAGE_LIMIT
        val offsets = mutableListOf<Int>()
        var offset = sync.next_offset
        while (offset < sync.remote_total && offsets.size < count) {
            offsets.add(offset)
            offset += pageSize
        }
        return offsets
    }

    private suspend fun insertPage(page: LibraryPage<SpotifyPlaylistSimple>, startSortIndex: Int) {
        val entities = page.items.mapIndexed { index, playlist ->
            playlist.toEntity(sortIndex = startSortIndex + index)
        }
        if (entities.isNotEmpty()) {
            playlistDao.insertAll(entities)
        }
    }

    private suspend fun updateSyncState(
        page: LibraryPage<SpotifyPlaylistSimple>,
        nextOffset: Int,
        isRefresh: Boolean,
    ) {
        val existing = syncDao.get(LibraryResource.USER_PLAYLISTS)
        val head = page.items.firstOrNull()
        syncDao.upsert(
            LibrarySyncStateEntity(
                resource = LibraryResource.USER_PLAYLISTS,
                remote_total = page.total,
                head_added_at = if (isRefresh) head?.snapshotId else existing?.head_added_at,
                head_id = if (isRefresh) head?.id else existing?.head_id,
                next_offset = nextOffset,
                last_synced_at = System.currentTimeMillis(),
            ),
        )
    }
}
