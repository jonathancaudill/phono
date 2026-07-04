package com.lightphone.spotify.data

import com.lightphone.spotify.data.local.DetailCacheRepository
import com.lightphone.spotify.data.local.LibraryRepository
import com.lightphone.spotify.data.SpotifyPlaylistDetail
import com.lightphone.spotify.data.SpotifyPlaylistSimple
import com.lightphone.spotify.data.SpotifyPlaylistTrackItem
import com.lightphone.spotify.data.local.PlaylistEntity
import com.lightphone.spotify.data.webapi.SpotifyWebApi
import com.lightphone.spotify.data.webapi.WebApiAuthException
import com.lightphone.spotify.data.toMetadata
import com.lightphone.spotify.ffi.TrackInfo
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * UI-facing track model for playback.
 */
data class TrackMetadata(
    val uri: String,
    val title: String,
    val artists: String,
    val album: String,
    val durationMs: Long,
    val artUrl: String?,
    val albumId: String? = null,
    val artistIds: List<String> = emptyList(),
)

fun TrackInfo.toMetadata(): TrackMetadata = TrackMetadata(
    uri = uri,
    title = title,
    artists = artists,
    album = album,
    durationMs = durationMs,
    artUrl = artUrl,
)

data class PlaylistDetailResult(
    val detail: SpotifyPlaylistDetail,
    val tracks: List<SpotifyPlaylistTrackItem>,
    val currentUserId: String,
    val isEditable: Boolean,
    val isInLibrary: Boolean,
)

/** Metadata via Spotify Web API. */
class SpotifyRepository(
    private val webApi: SpotifyWebApi,
    private val libraryRepository: LibraryRepository,
    private val detailCache: DetailCacheRepository,
) {
    private val searchCache = ConcurrentHashMap<String, EphemeralEntry<SearchResults>>()
    private val ephemeralAlbumCache = ConcurrentHashMap<String, EphemeralEntry<AlbumDetailResult>>()
    private val ephemeralPlaylistCache = ConcurrentHashMap<String, EphemeralEntry<PlaylistDetailResult>>()
    private var dailyMixesCache: Pair<Long, List<SpotifyPlaylistSimple>>? = null
    private var currentUserIdCache: String? = null

    suspend fun albumDetail(albumId: String): AlbumDetailResult {
        val now = System.currentTimeMillis()
        ephemeralAlbumCache[albumId]?.let { entry ->
            if (now - entry.at < CACHE_TTL_MS) {
                entry.touch(now)
                return entry.value
            }
            ephemeralAlbumCache.remove(albumId)
        }
        detailCache.getPinnedAlbumDetail(albumId)?.let { (album, isSaved) ->
            return AlbumDetailResult(album = album, isSaved = isSaved)
        }

        val album = webApi.album(albumId)
        val uri = album.uri.ifBlank { "spotify:album:$albumId" }
        val isSaved = webApi.libraryContains(listOf(uri)).firstOrNull()
            ?: detailCache.isSavedAlbumCached(albumId)
        val result = AlbumDetailResult(album = album, isSaved = isSaved)
        if (detailCache.isSavedAlbumCached(albumId)) {
            detailCache.putPinnedAlbumDetail(albumId, album, isSaved)
        } else {
            putEphemeralAlbum(albumId, result, now)
        }
        return result
    }

    fun artistDetail(artistId: String): ArtistDetailResult {
        val artist = webApi.artist(artistId)
        val albums = webApi.artistAlbums(artistId)
        return ArtistDetailResult(
            artist = artist,
            topTracks = emptyList(),
            albums = albums,
        )
    }

    fun search(query: String, limitPerType: Int = 8): SearchResults {
        val key = query.trim()
        if (key.isEmpty()) return SearchResults(query = "")
        val now = System.currentTimeMillis()
        searchCache[key]?.let { entry ->
            if (now - entry.at < SEARCH_TTL_MS) {
                entry.touch(now)
                return entry.value
            }
            searchCache.remove(key)
        }
        val apiResults = webApi.search(key, limitPerType)
        val items = apiResults.toSearchResults(key)
        evictOldestIfNeeded(searchCache, SEARCH_CACHE_CAP)
        searchCache[key] = EphemeralEntry(items, now, now)
        return items
    }

    fun playlistTracks(playlistId: String, limit: Int = 100): List<TrackMetadata> =
        webApi.playlistItems(playlistId, limit).map { it.toMetadata() }

    fun currentUserId(): String {
        currentUserIdCache?.let { return it }
        val id = webApi.currentUser().id
        currentUserIdCache = id
        return id
    }

    suspend fun playlistDetail(playlistId: String, trackLimit: Int = 500): PlaylistDetailResult {
        val now = System.currentTimeMillis()
        ephemeralPlaylistCache[playlistId]?.let { entry ->
            if (now - entry.at < CACHE_TTL_MS) {
                entry.touch(now)
                return entry.value
            }
            ephemeralPlaylistCache.remove(playlistId)
        }
        val authoritativeSnapshot = libraryRepository.getPlaylistSnapshot(playlistId)
        detailCache.getPinnedPlaylistDetail(playlistId, authoritativeSnapshot)?.let { (detail, tracks, _) ->
            val userId = currentUserIdSuspend()
            val isEditable = detail.owner?.id == userId || detail.collaborative
            val uri = detail.uri.ifBlank { "spotify:playlist:$playlistId" }
            val isInLibrary = webApi.libraryContains(listOf(uri)).firstOrNull() ?: false
            return PlaylistDetailResult(
                detail = detail,
                tracks = tracks,
                currentUserId = userId,
                isEditable = isEditable,
                isInLibrary = isInLibrary,
            )
        }

        val userId = currentUserIdSuspend()
        val detail = webApi.playlist(playlistId)
        val tracks = paginatePlaylistTrackItems(playlistId, trackLimit)
        val isEditable = detail.owner?.id == userId || detail.collaborative
        val uri = detail.uri.ifBlank { "spotify:playlist:$playlistId" }
        val isInLibrary = webApi.libraryContains(listOf(uri)).firstOrNull() ?: false
        val result = PlaylistDetailResult(
            detail = detail,
            tracks = tracks,
            currentUserId = userId,
            isEditable = isEditable,
            isInLibrary = isInLibrary,
        )
        if (detailCache.isUserPlaylist(playlistId)) {
            detailCache.putPinnedPlaylistDetail(playlistId, detail, tracks, detail.snapshotId)
        } else {
            putEphemeralPlaylist(playlistId, result, now)
        }
        return result
    }

    suspend fun playlistsContainingTrack(
        trackUri: String,
        playlistIds: List<String>,
    ): Set<String> {
        val normalized = normalizeUri(trackUri)
        val idSet = playlistIds.toSet()
        val found = detailCache.playlistIdsContaining(normalized)
            .filterTo(mutableSetOf()) { it in idSet }
        for (playlistId in playlistIds) {
            if (playlistId in found) continue
            val inEphemeral = ephemeralPlaylistCache[playlistId]?.value?.tracks?.any { item ->
                item.track?.uri?.let { normalizeUri(it) } == normalized
            } == true
            if (inEphemeral) found.add(playlistId)
        }
        return found
    }

    /**
     * Snapshot-gated background index of editable playlist track URIs.
     * Only re-fetches playlists whose [PlaylistEntity.snapshot_id] differs from the last index.
     */
    suspend fun syncPlaylistUriIndex() {
        val userId = runCatching { currentUserIdSuspend() }.getOrNull() ?: return
        val editable = editablePlaylists(userId)
        for (playlist in editable) {
            if (!detailCache.needsUriReindex(playlist.playlist_id, playlist.snapshot_id)) continue
            runCatching {
                val items = paginatePlaylistTrackItems(playlist.playlist_id, limit = URI_INDEX_TRACK_LIMIT)
                val uris = items.mapNotNull { item ->
                    item.track?.uri?.takeIf { it.isNotBlank() }
                }
                detailCache.replaceTrackUris(playlist.playlist_id, uris, playlist.snapshot_id)
            }.onFailure { e ->
                android.util.Log.w(
                    "PlaylistUriIndex",
                    "index failed for ${playlist.playlist_id}",
                    e,
                )
            }
        }
    }

    suspend fun isSavedAlbumCached(albumId: String): Boolean =
        detailCache.isSavedAlbumCached(albumId)

    suspend fun createPlaylist(name: String, isPublic: Boolean): SpotifyPlaylistSimple {
        val userId = currentUserIdSuspend()
        val created = webApi.createPlaylist(userId, name.trim(), isPublic)
        libraryRepository.prependPlaylist(created)
        return created
    }

    suspend fun renamePlaylist(playlistId: String, name: String): SpotifyPlaylistDetail {
        val trimmed = name.trim()
        webApi.changePlaylistDetails(playlistId, name = trimmed)
        val detail = webApi.playlist(playlistId)
        libraryRepository.updatePlaylistName(playlistId, trimmed)
        val snapshot = detail.snapshotId?.takeIf { it.isNotBlank() }
            ?: error("Playlist detail missing snapshot_id after rename")
        onPlaylistMutated(playlistId, snapshot, invalidateTracks = true)
        return detail
    }

    suspend fun addTrackToPlaylist(
        playlistId: String,
        uri: String,
        snapshotId: String? = null,
        position: Int? = null,
    ): String {
        val normalized = normalizeUri(uri)
        val resolvedSnapshot = snapshotId
            ?: libraryRepository.getPlaylistSnapshot(playlistId)
            ?: detailCache.indexedSnapshotId(playlistId)
        val newSnapshot = webApi.addPlaylistItems(
            playlistId,
            listOf(normalized),
            position,
            resolvedSnapshot,
        )
        onPlaylistMutated(
            playlistId = playlistId,
            newSnapshot = newSnapshot,
            addedUri = normalized,
        )
        return newSnapshot
    }

    suspend fun removeTrackFromPlaylist(
        playlistId: String,
        uri: String,
        snapshotId: String?,
    ): String {
        val normalized = normalizeUri(uri)
        val newSnapshot = webApi.removePlaylistItems(playlistId, listOf(normalized), snapshotId)
        onPlaylistMutated(
            playlistId = playlistId,
            newSnapshot = newSnapshot,
            removedUri = normalized,
        )
        return newSnapshot
    }

    suspend fun reorderPlaylistTrack(
        playlistId: String,
        fromIndex: Int,
        toIndex: Int,
        snapshotId: String?,
    ): String {
        if (fromIndex == toIndex) {
            return snapshotId ?: libraryRepository.getPlaylistSnapshot(playlistId)
                ?: error("No snapshot_id for playlist")
        }
        val insertBefore = if (toIndex > fromIndex) toIndex + 1 else toIndex
        val newSnapshot = webApi.reorderPlaylistItems(
            playlistId = playlistId,
            rangeStart = fromIndex,
            insertBefore = insertBefore,
            rangeLength = 1,
            snapshotId = snapshotId,
        )
        onPlaylistMutated(playlistId, newSnapshot, invalidateTracks = true)
        return newSnapshot
    }

    suspend fun followPlaylist(playlistId: String) {
        webApi.followPlaylist(playlistId)
        val detail = webApi.playlist(playlistId)
        libraryRepository.prependPlaylist(detail.toPlaylistSimple())
    }

    suspend fun unfollowPlaylist(playlistId: String) {
        webApi.unfollowPlaylist(playlistId)
        libraryRepository.removePlaylist(playlistId)
        detailCache.clearPlaylistUriIndex(playlistId)
    }

    suspend fun editablePlaylists(userId: String? = null): List<PlaylistEntity> {
        val resolvedUserId = userId ?: currentUserIdSuspend()
        return libraryRepository.playlistsSnapshot().filter { playlist ->
            playlist.owner_id == resolvedUserId || playlist.is_collaborative
        }
    }

    suspend fun currentUserIdSuspend(): String {
        currentUserIdCache?.let { return it }
        val id = webApi.currentUserSuspend().id
        currentUserIdCache = id
        return id
    }

    private suspend fun paginatePlaylistTrackItems(playlistId: String, limit: Int): List<SpotifyPlaylistTrackItem> {
        val results = mutableListOf<SpotifyPlaylistTrackItem>()
        var offset = 0
        while (results.size < limit) {
            val page = webApi.playlistItemsPage(playlistId, offset)
            if (page.items.isEmpty()) break
            results.addAll(page.items)
            offset += page.items.size
            if (offset >= page.total) break
        }
        return results.take(limit)
    }

    fun isTrackSaved(uri: String): Boolean =
        webApi.libraryContains(listOf(normalizeUri(uri))).firstOrNull() ?: false

    suspend fun saveTrack(uri: String) {
        val normalized = normalizeUri(uri)
        webApi.saveLibrary(listOf(normalized))
        val meta = trackMetadataForUri(normalized)
            ?: throw IllegalStateException("Could not load track metadata after save")
        libraryRepository.prependLikedTrack(meta)
    }

    suspend fun removeTrack(uri: String) {
        val normalized = normalizeUri(uri)
        webApi.removeLibrary(listOf(normalized))
        libraryRepository.removeLikedTrack(normalized)
    }

    suspend fun saveAlbum(albumId: String) {
        webApi.saveLibrary(listOf("spotify:album:$albumId"))
        val detail = webApi.album(albumId)
        libraryRepository.prependSavedAlbum(
            SpotifySavedAlbum(
                addedAt = Instant.now().toString(),
                album = SpotifyAlbumSimple(
                    id = detail.id,
                    name = detail.name,
                    uri = detail.uri.ifBlank { "spotify:album:$albumId" },
                    images = detail.images,
                    artists = detail.artists,
                ),
            ),
        )
        onAlbumMutated(albumId, isSaved = true)
    }

    suspend fun removeAlbum(albumId: String) {
        webApi.removeLibrary(listOf("spotify:album:$albumId"))
        libraryRepository.removeSavedAlbum(albumId)
        onAlbumMutated(albumId, isSaved = false)
    }

    fun albumTracks(albumId: String): List<TrackMetadata> =
        webApi.album(albumId).tracks.items.map { it.toMetadata() }

    /** Single-track metadata from Web API (now-playing art, title, liked checks). */
    fun trackMetadataForUri(uri: String): TrackMetadata? {
        val id = trackIdFromUri(uri)
        if (id.isBlank()) return null
        return runCatching { webApi.track(id).toMetadata() }.getOrNull()
    }

    /**
     * Daily Mix / Made-For-You playlists from the user's followed playlists
     * matching well-known editorial names.
     */
    fun dailyMixes(): List<SpotifyPlaylistSimple> {
        val now = System.currentTimeMillis()
        dailyMixesCache?.let { (at, items) ->
            if (now - at < CACHE_TTL_MS) return items
        }
        val all = mutableListOf<SpotifyPlaylistSimple>()
        var offset = 0
        while (offset < DAILY_MIXES_SCAN_LIMIT) {
            val page = webApi.myPlaylistsPage(offset)
            if (page.items.isEmpty()) break
            all.addAll(page.items)
            offset += page.items.size
            if (offset >= page.total) break
        }
        val items = all.filter { playlist ->
            playlist.name.contains("Daily Mix", ignoreCase = true) ||
                playlist.name.contains("Discover Weekly", ignoreCase = true) ||
                playlist.name.contains("Release Radar", ignoreCase = true) ||
                playlist.name.contains("Made For You", ignoreCase = true)
        }
        dailyMixesCache = now to items
        return items
    }

    suspend fun clearLibraryCache() {
        libraryRepository.clearAll()
    }

    fun clearSessionCaches() {
        searchCache.clear()
        ephemeralAlbumCache.clear()
        ephemeralPlaylistCache.clear()
        dailyMixesCache = null
        currentUserIdCache = null
    }

    private suspend fun onPlaylistMutated(
        playlistId: String,
        newSnapshot: String,
        addedUri: String? = null,
        removedUri: String? = null,
        invalidateTracks: Boolean = false,
    ) {
        libraryRepository.updatePlaylistSnapshot(playlistId, newSnapshot)
        when {
            addedUri != null -> detailCache.addTrackUri(playlistId, addedUri, newSnapshot)
            removedUri != null -> detailCache.removeTrackUri(playlistId, removedUri, newSnapshot)
            invalidateTracks -> detailCache.invalidatePinnedPlaylistTracks(playlistId)
        }
        ephemeralPlaylistCache.remove(playlistId)
    }

    private suspend fun onAlbumMutated(albumId: String, isSaved: Boolean) {
        ephemeralAlbumCache.remove(albumId)
        if (isSaved) {
            detailCache.patchAlbumIsSaved(albumId, isSaved = true)
        } else {
            detailCache.deleteAlbumDetail(albumId)
        }
    }

    private fun invalidateEphemeralPlaylist(playlistId: String) {
        ephemeralPlaylistCache.remove(playlistId)
    }

    private fun invalidateEphemeralAlbum(albumId: String) {
        ephemeralAlbumCache.remove(albumId)
    }

    private data class EphemeralEntry<T>(
        val value: T,
        var at: Long,
        var lastAccessedAt: Long,
    ) {
        fun touch(now: Long) {
            lastAccessedAt = now
        }
    }

    private fun putEphemeralAlbum(albumId: String, result: AlbumDetailResult, now: Long) {
        evictOldestIfNeeded(ephemeralAlbumCache, EPHEMERAL_ALBUM_CAP)
        ephemeralAlbumCache[albumId] = EphemeralEntry(result, now, now)
    }

    private fun putEphemeralPlaylist(playlistId: String, result: PlaylistDetailResult, now: Long) {
        evictOldestIfNeeded(ephemeralPlaylistCache, EPHEMERAL_PLAYLIST_CAP)
        ephemeralPlaylistCache[playlistId] = EphemeralEntry(result, now, now)
    }

    private fun <T> evictOldestIfNeeded(
        cache: ConcurrentHashMap<String, EphemeralEntry<T>>,
        cap: Int,
    ) {
        if (cache.size < cap) return
        val oldest = cache.entries.minByOrNull { it.value.lastAccessedAt }?.key ?: return
        cache.remove(oldest)
    }

    companion object {
        private const val CACHE_TTL_MS = 5 * 60_000L
        private const val SEARCH_TTL_MS = 2 * 60_000L
        private const val SEARCH_CACHE_CAP = 25
        private const val EPHEMERAL_ALBUM_CAP = 20
        private const val EPHEMERAL_PLAYLIST_CAP = 10
        private const val URI_INDEX_TRACK_LIMIT = 10_000
        private const val DAILY_MIXES_SCAN_LIMIT = 200
    }

    private fun normalizeUri(uri: String): String = uri.substringBefore('?').trim()

    private fun trackIdFromUri(uri: String): String =
        normalizeUri(uri).substringAfterLast(':')
}

data class AlbumDetailResult(
    val album: SpotifyAlbumDetail,
    val isSaved: Boolean,
)

data class ArtistDetailResult(
    val artist: SpotifyArtistDetail,
    val topTracks: List<SpotifyTrack>,
    val albums: List<SpotifyAlbumSimple>,
)

private fun SpotifySearchResults.toSearchResults(query: String): SearchResults {
    val base = SearchResults(
        query = query,
        artists = artists?.items.orEmpty().filterNotNull(),
        albums = albums?.items.orEmpty().filterNotNull(),
        tracks = tracks?.items.orEmpty().filterNotNull(),
        playlists = playlists?.items.orEmpty().filterNotNull(),
    )
    val ranked = SearchRanking.rank(query, base)
    return base.copy(
        topResult = ranked.topResult,
        rankedItems = ranked.rankedItems,
    )
}

fun mapWebApiError(e: Throwable): String = when (e) {
    is WebApiAuthException -> e.message ?: "Web API session expired — re-authorize Step 2"
    is android.os.NetworkOnMainThreadException ->
        "Network call on main thread — try again."
    else -> {
        val msg = e.message.orEmpty()
        when {
            msg.startsWith("HTTP 429") -> "Spotify is busy — wait a moment and try again."
            msg.startsWith("HTTP 401") || msg.startsWith("HTTP 403") ->
                "Web API session expired — re-authorize Step 2."
            msg.startsWith("HTTP") -> "Can't reach Spotify right now. Try again."
            else -> e.message?.takeIf { it.isNotBlank() }
                ?: "${e::class.simpleName ?: "Error"} — try again."
        }
    }
}
