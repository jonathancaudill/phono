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
import kotlin.math.min

/** Advance [next_offset] page-by-page so empty API pages cannot stall fill. */
private fun computeBatchNextOffset(
    startOffset: Int,
    remoteTotal: Int,
    pages: List<Pair<Int, Int>>,
    pageSize: Int,
): Int {
    var nextOffset = startOffset
    pages.sortedBy { it.first }.forEach { (offset, itemCount) ->
        if (offset < nextOffset) return@forEach
        nextOffset = if (itemCount > 0) {
            offset + itemCount
        } else {
            min(offset + pageSize, remoteTotal)
        }
    }
    return nextOffset
}

/**
 * Head identity for skip/delta decisions.
 *
 * Timestamps are compared only when both sides are non-null so optimistic local
 * mutations (null / non-API clocks) and native playlist snapshots (often null)
 * do not force a full wipe on every relaunch.
 */
internal fun libraryHeadMatches(
    syncHeadId: String?,
    syncHeadAddedAt: String?,
    remoteHeadId: String?,
    remoteHeadAddedAt: String?,
): Boolean {
    if (syncHeadId != remoteHeadId) return false
    if (syncHeadAddedAt != null && remoteHeadAddedAt != null && syncHeadAddedAt != remoteHeadAddedAt) {
        return false
    }
    return true
}

/** How many pages to scan for the previous head before falling back to a full reload. */
private const val MAX_DELTA_PAGES = 5

/**
 * Network fetch + Room persistence for liked tracks and saved albums.
 *
 * [refresh] is a true delta:
 * - unchanged head → no rewrite (incomplete fills resume via [fillRemainingParallel])
 * - new items prepended ahead of the previous head → insert only the delta
 * - otherwise → clear + page-0 rewrite (first login / large reorder / head removed)
 */
internal class LikedTracksSync(
    private val database: PhonoDatabase,
    private val pageFetcher: suspend (offset: Int) -> LibraryPage<SpotifySavedTrack>,
) {
    private val trackDao = database.likedTrackDao()
    private val syncDao = database.librarySyncDao()

    /** @return true when local rows were rewritten (full or delta). */
    suspend fun refresh(): Boolean {
        val page = pageFetcher(0)
        val head = page.items.firstOrNull()
        val sync = syncDao.get(LibraryResource.LIKED_TRACKS)
        val remoteHeadId = head?.track?.uri
        val remoteHeadAddedAt = head?.addedAt

        if (sync != null &&
            libraryHeadMatches(sync.head_id, sync.head_added_at, remoteHeadId, remoteHeadAddedAt)
        ) {
            if (sync.remote_total != page.total) {
                syncDao.upsert(
                    sync.copy(
                        remote_total = page.total,
                        next_offset = min(sync.next_offset, page.total),
                        last_synced_at = System.currentTimeMillis(),
                    ),
                )
            }
            return false
        }

        if (sync?.head_id != null && tryDeltaPrepend(page, sync)) {
            return true
        }

        database.withTransaction {
            trackDao.clearAll()
            syncDao.delete(LibraryResource.LIKED_TRACKS)
            insertPage(page, startSortIndex = 0)
            updateSyncState(page, nextOffset = page.items.size, isRefresh = true)
        }
        return true
    }

    /**
     * When the library grew at the front, insert only the new head items and keep
     * the existing cache. Returns false when the previous head is not found quickly.
     */
    private suspend fun tryDeltaPrepend(
        firstPage: LibraryPage<SpotifySavedTrack>,
        sync: LibrarySyncStateEntity,
    ): Boolean {
        val oldHeadId = sync.head_id ?: return false
        val newItems = mutableListOf<SpotifySavedTrack>()
        var page = firstPage
        var pagesScanned = 0
        while (pagesScanned < MAX_DELTA_PAGES) {
            val idx = page.items.indexOfFirst { it.track?.uri == oldHeadId }
            if (idx >= 0) {
                newItems += page.items.take(idx)
                if (newItems.isEmpty()) {
                    // Head id matched after timestamp-only mismatch — treat as unchanged.
                    syncDao.upsert(
                        sync.copy(
                            remote_total = page.total,
                            head_added_at = page.items.firstOrNull()?.addedAt ?: sync.head_added_at,
                            next_offset = min(sync.next_offset, page.total),
                            last_synced_at = System.currentTimeMillis(),
                        ),
                    )
                    return true
                }
                database.withTransaction {
                    trackDao.shiftSortIndicesBy(newItems.size)
                    insertPage(
                        LibraryPage(items = newItems, total = page.total, offset = 0),
                        startSortIndex = 0,
                    )
                    val newHead = newItems.first()
                    syncDao.upsert(
                        sync.copy(
                            remote_total = page.total,
                            head_id = newHead.track?.uri,
                            head_added_at = newHead.addedAt,
                            next_offset = sync.next_offset + newItems.size,
                            last_synced_at = System.currentTimeMillis(),
                        ),
                    )
                }
                return true
            }
            newItems += page.items
            pagesScanned++
            val nextOffset = pagesScanned * SpotifyWebApi.LIBRARY_PAGE_LIMIT
            if (nextOffset >= page.total || page.items.isEmpty()) break
            page = pageFetcher(nextOffset)
        }
        return false
    }

    suspend fun append(): Boolean {
        val sync = syncDao.get(LibraryResource.LIKED_TRACKS) ?: return false
        val offset = sync.next_offset
        if (offset >= sync.remote_total) return false

        val page = pageFetcher(offset)
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
                    async { offset to pageFetcher(offset) }
                }.awaitAll()
            }

            database.withTransaction {
                pages.sortedBy { it.first }.forEach { (offset, page) ->
                    if (page.items.isNotEmpty()) {
                        insertPage(page, startSortIndex = offset)
                        inserted += page.items.size
                    }
                }
                val syncNow = syncDao.get(LibraryResource.LIKED_TRACKS) ?: return@withTransaction
                val nextOffset = computeBatchNextOffset(
                    syncNow.next_offset,
                    syncNow.remote_total,
                    pages.map { (o, p) -> o to p.items.size },
                    SpotifyWebApi.LIBRARY_PAGE_LIMIT,
                )
                val lastWithItems = pages.filter { it.second.items.isNotEmpty() }.maxByOrNull { it.first }
                if (lastWithItems != null) {
                    updateSyncState(lastWithItems.second, nextOffset = nextOffset, isRefresh = false)
                } else {
                    syncDao.upsert(
                        syncNow.copy(
                            next_offset = nextOffset,
                            last_synced_at = System.currentTimeMillis(),
                        ),
                    )
                }
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
    private val database: PhonoDatabase,
    private val pageFetcher: suspend (offset: Int) -> LibraryPage<SpotifySavedAlbum>,
) {
    private val albumDao = database.savedAlbumDao()
    private val syncDao = database.librarySyncDao()

    suspend fun refresh(): Boolean {
        val page = pageFetcher(0)
        val head = page.items.firstOrNull()
        val sync = syncDao.get(LibraryResource.SAVED_ALBUMS)
        val remoteHeadId = head?.album?.id
        val remoteHeadAddedAt = head?.addedAt

        if (sync != null &&
            libraryHeadMatches(sync.head_id, sync.head_added_at, remoteHeadId, remoteHeadAddedAt)
        ) {
            if (sync.remote_total != page.total) {
                syncDao.upsert(
                    sync.copy(
                        remote_total = page.total,
                        next_offset = min(sync.next_offset, page.total),
                        last_synced_at = System.currentTimeMillis(),
                    ),
                )
            }
            return false
        }

        if (sync?.head_id != null && tryDeltaPrepend(page, sync)) {
            return true
        }

        database.withTransaction {
            albumDao.clearAll()
            syncDao.delete(LibraryResource.SAVED_ALBUMS)
            insertPage(page, startSortIndex = 0)
            updateSyncState(page, nextOffset = page.items.size, isRefresh = true)
        }
        return true
    }

    private suspend fun tryDeltaPrepend(
        firstPage: LibraryPage<SpotifySavedAlbum>,
        sync: LibrarySyncStateEntity,
    ): Boolean {
        val oldHeadId = sync.head_id ?: return false
        val newItems = mutableListOf<SpotifySavedAlbum>()
        var page = firstPage
        var pagesScanned = 0
        while (pagesScanned < MAX_DELTA_PAGES) {
            val idx = page.items.indexOfFirst { it.album?.id == oldHeadId }
            if (idx >= 0) {
                newItems += page.items.take(idx)
                if (newItems.isEmpty()) {
                    syncDao.upsert(
                        sync.copy(
                            remote_total = page.total,
                            head_added_at = page.items.firstOrNull()?.addedAt ?: sync.head_added_at,
                            next_offset = min(sync.next_offset, page.total),
                            last_synced_at = System.currentTimeMillis(),
                        ),
                    )
                    return true
                }
                database.withTransaction {
                    albumDao.shiftSortIndicesBy(newItems.size)
                    insertPage(
                        LibraryPage(items = newItems, total = page.total, offset = 0),
                        startSortIndex = 0,
                    )
                    val newHead = newItems.first()
                    syncDao.upsert(
                        sync.copy(
                            remote_total = page.total,
                            head_id = newHead.album?.id,
                            head_added_at = newHead.addedAt,
                            next_offset = sync.next_offset + newItems.size,
                            last_synced_at = System.currentTimeMillis(),
                        ),
                    )
                }
                return true
            }
            newItems += page.items
            pagesScanned++
            val nextOffset = pagesScanned * SpotifyWebApi.LIBRARY_PAGE_LIMIT
            if (nextOffset >= page.total || page.items.isEmpty()) break
            page = pageFetcher(nextOffset)
        }
        return false
    }

    suspend fun append(): Boolean {
        val sync = syncDao.get(LibraryResource.SAVED_ALBUMS) ?: return false
        val offset = sync.next_offset
        if (offset >= sync.remote_total) return false

        val page = pageFetcher(offset)
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
                    async { offset to pageFetcher(offset) }
                }.awaitAll()
            }

            database.withTransaction {
                pages.sortedBy { it.first }.forEach { (offset, page) ->
                    if (page.items.isNotEmpty()) {
                        insertPage(page, startSortIndex = offset)
                        inserted += page.items.size
                    }
                }
                val syncNow = syncDao.get(LibraryResource.SAVED_ALBUMS) ?: return@withTransaction
                val nextOffset = computeBatchNextOffset(
                    syncNow.next_offset,
                    syncNow.remote_total,
                    pages.map { (o, p) -> o to p.items.size },
                    SpotifyWebApi.LIBRARY_PAGE_LIMIT,
                )
                val lastWithItems = pages.filter { it.second.items.isNotEmpty() }.maxByOrNull { it.first }
                if (lastWithItems != null) {
                    updateSyncState(lastWithItems.second, nextOffset = nextOffset, isRefresh = false)
                } else {
                    syncDao.upsert(
                        syncNow.copy(
                            next_offset = nextOffset,
                            last_synced_at = System.currentTimeMillis(),
                        ),
                    )
                }
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
    private val database: PhonoDatabase,
    private val pageFetcher: suspend (offset: Int) -> LibraryPage<SpotifyPlaylistSimple>,
) {
    // sort_index follows saved-playlist page order (native rootlist or Web API fallback).
    private val playlistDao = database.playlistDao()
    private val syncDao = database.librarySyncDao()

    suspend fun refresh(): Boolean {
        val page = pageFetcher(0)
        val head = page.items.firstOrNull()
        val sync = syncDao.get(LibraryResource.USER_PLAYLISTS)
        val remoteHeadId = head?.id
        // snapshotId stands in for head_added_at; native rootlist often leaves it null.
        val remoteHeadRev = head?.snapshotId

        if (sync != null &&
            libraryHeadMatches(sync.head_id, sync.head_added_at, remoteHeadId, remoteHeadRev)
        ) {
            if (sync.remote_total != page.total) {
                syncDao.upsert(
                    sync.copy(
                        remote_total = page.total,
                        next_offset = min(sync.next_offset, page.total),
                        last_synced_at = System.currentTimeMillis(),
                    ),
                )
            }
            // Patch owner labels in place — never a reason to wipe the table.
            patchOwnerNames(page.items)
            return false
        }

        // Playlist order can reshuffle (not just grow at the head), so a head
        // mismatch falls back to a full rewrite. Lists are small vs liked tracks.
        database.withTransaction {
            playlistDao.clearAll()
            syncDao.delete(LibraryResource.USER_PLAYLISTS)
            insertPage(page, startSortIndex = 0)
            updateSyncState(page, nextOffset = page.items.size, isRefresh = true)
        }
        return true
    }

    private suspend fun patchOwnerNames(items: List<SpotifyPlaylistSimple>) {
        for (playlist in items) {
            val owner = playlist.owner ?: continue
            val display = owner.displayName?.takeIf { it.isNotBlank() && it != owner.id } ?: continue
            playlistDao.updateOwnerName(playlist.id, display)
        }
    }

    suspend fun append(): Boolean {
        val sync = syncDao.get(LibraryResource.USER_PLAYLISTS) ?: return false
        val offset = sync.next_offset
        if (offset >= sync.remote_total) return false

        val page = pageFetcher(offset)
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
                    async { offset to pageFetcher(offset) }
                }.awaitAll()
            }

            database.withTransaction {
                pages.sortedBy { it.first }.forEach { (offset, page) ->
                    if (page.items.isNotEmpty()) {
                        insertPage(page, startSortIndex = offset)
                        inserted += page.items.size
                    }
                }
                val syncNow = syncDao.get(LibraryResource.USER_PLAYLISTS) ?: return@withTransaction
                val nextOffset = computeBatchNextOffset(
                    syncNow.next_offset,
                    syncNow.remote_total,
                    pages.map { (o, p) -> o to p.items.size },
                    SpotifyWebApi.LIBRARY_PAGE_LIMIT,
                )
                val lastWithItems = pages.filter { it.second.items.isNotEmpty() }.maxByOrNull { it.first }
                if (lastWithItems != null) {
                    updateSyncState(lastWithItems.second, nextOffset = nextOffset, isRefresh = false)
                } else {
                    syncDao.upsert(
                        syncNow.copy(
                            next_offset = nextOffset,
                            last_synced_at = System.currentTimeMillis(),
                        ),
                    )
                }
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
