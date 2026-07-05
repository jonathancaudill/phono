package com.lightphone.spotify.data

import com.lightphone.spotify.data.local.DetailCacheRepository
import com.lightphone.spotify.data.local.LibraryRepository
import com.lightphone.spotify.data.SpotifyPlaylistDetail
import com.lightphone.spotify.data.SpotifyPlaylistOwner
import com.lightphone.spotify.data.SpotifyPlaylistSimple
import com.lightphone.spotify.data.SpotifyPlaylistTrackItem
import com.lightphone.spotify.data.local.PlaylistEntity
import com.lightphone.spotify.data.SpotifyTrack
import com.lightphone.spotify.data.native.NativeMetadataAdapter
import com.lightphone.spotify.data.native.NativeMetadataGateway
import com.lightphone.spotify.data.native.NativeSessionRequiredException
import com.lightphone.spotify.data.native.mapNativeError
import com.lightphone.spotify.data.webapi.SpotifyWebApi
import com.lightphone.spotify.data.webapi.WebApiAuthException
import com.lightphone.spotify.data.toMetadata
import com.lightphone.spotify.ffi.TrackInfo
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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

fun TrackInfo.toSpotifyTrack(): SpotifyTrack = SpotifyTrack(
    id = uri.removePrefix("spotify:track:").substringBefore('?'),
    name = title,
    uri = uri,
    artists = artists.split(", ")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { name -> SpotifyArtist(name = name) },
    album = album.takeIf { it.isNotBlank() }?.let { SpotifyAlbumSimple(name = it) },
    durationMs = durationMs,
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
    /** Login5 spclient gateway (Step 1). Required for playlist/artist paths. */
    var nativeMetadata: NativeMetadataGateway? = null

    private fun nativeGateway(): NativeMetadataGateway {
        val gateway = nativeMetadata
        if (gateway == null || !gateway.isLoggedIn()) {
            throw NativeSessionRequiredException()
        }
        return gateway
    }

    private val searchCache = ConcurrentHashMap<String, EphemeralEntry<SearchResults>>()
    private val ephemeralAlbumCache = ConcurrentHashMap<String, EphemeralEntry<AlbumDetailResult>>()
    private val ephemeralPlaylistCache = ConcurrentHashMap<String, EphemeralEntry<PlaylistDetailResult>>()
    private var dailyMixesCache: Pair<Long, List<SpotifyPlaylistSimple>>? = null
    private var currentUserIdCache: String? = null
    private val ownerDisplayNameCache = ConcurrentHashMap<String, String>()

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
        val bundle = nativeGateway().artistDetail(
            artistId = artistId,
            albumLimit = 50,
            topTrackLimit = 10,
        )
        return NativeMetadataAdapter.toArtistDetailResult(bundle)
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

    fun playlistTracks(playlistId: String, limit: Int = 100): List<TrackMetadata> {
        val bundle = nativeGateway().playlistDetail(playlistId, limit.coerceIn(1, 500))
        return bundle.tracks.mapNotNull { it.track.toSpotifyTrack().toMetadata() }
    }

    fun currentUserId(): String {
        currentUserIdCache?.let { return it }
        val id = nativeMetadata?.takeIf { it.isLoggedIn() }?.sessionUsername()
            ?: webApi.currentUser().id
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
            val resolvedDetail = detail.copy(owner = resolveOwner(detail.owner))
            val isEditable = resolvedDetail.owner?.id == userId || resolvedDetail.collaborative
            val isInLibrary = libraryRepository.playlistsSnapshot().any { it.playlist_id == playlistId }
            return PlaylistDetailResult(
                detail = resolvedDetail,
                tracks = tracks,
                currentUserId = userId,
                isEditable = isEditable,
                isInLibrary = isInLibrary,
            )
        }

        val gw = nativeGateway()
        val userId = currentUserIdSuspend()
        val bundle = gw.playlistDetail(playlistId, trackLimit)
        val isInLibrary = libraryRepository.playlistsSnapshot().any { it.playlist_id == playlistId }
        val result = resolvePlaylistDetailOwners(
            NativeMetadataAdapter.toPlaylistDetailResult(bundle, userId, isInLibrary),
        )
        result.detail.snapshotId?.takeIf { it.isNotBlank() }?.let { revision ->
            libraryRepository.updatePlaylistSnapshot(playlistId, revision)
        }
        result.detail.owner?.resolvedDisplayName()?.let { display ->
            libraryRepository.updatePlaylistOwnerName(playlistId, display)
        }
        if (detailCache.isUserPlaylist(playlistId)) {
            detailCache.putPinnedPlaylistDetail(
                playlistId,
                result.detail,
                result.tracks,
                result.detail.snapshotId,
            )
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
                val items = nativeGateway().playlistDetail(playlist.playlist_id, URI_INDEX_TRACK_LIMIT)
                    .tracks
                    .mapNotNull { row -> row.track.uri.takeIf { it.isNotBlank() } }
                detailCache.replaceTrackUris(playlist.playlist_id, items, playlist.snapshot_id)
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
        val created = nativeGateway().createPlaylist(name.trim(), isPublic)
        var simple = withResolvedOwner(NativeMetadataAdapter.toPlaylistSimple(created))
        if (simple.snapshotId.isNullOrBlank()) {
            val bundle = nativeGateway().playlistDetail(simple.id, trackLimit = 1)
            simple = withResolvedOwner(NativeMetadataAdapter.toPlaylistSimple(bundle.detail))
        }
        libraryRepository.prependPlaylist(simple)
        simple.snapshotId?.takeIf { it.isNotBlank() }?.let { revision ->
            onPlaylistMutated(simple.id, revision)
        }
        simple.owner?.resolvedDisplayName()?.let { display ->
            libraryRepository.updatePlaylistOwnerName(simple.id, display)
        }
        runCatching {
            nativeGateway().addToRootlist("spotify:playlist:${simple.id}")
        }
        return simple
    }

    suspend fun renamePlaylist(playlistId: String, name: String): SpotifyPlaylistDetail {
        val trimmed = name.trim()
        val revision = resolvePlaylistRevision(playlistId)
        val newRevision = nativeGateway().updatePlaylistMetadata(playlistId, revision, trimmed, null)
        val bundle = nativeGateway().playlistDetail(playlistId, trackLimit = 1)
        val detail = NativeMetadataAdapter.toPlaylistDetail(bundle.detail).copy(name = trimmed)
            .let { it.copy(owner = resolveOwner(it.owner)) }
        libraryRepository.updatePlaylistName(playlistId, trimmed)
        onPlaylistMutated(playlistId, newRevision, invalidateTracks = true)
        return detail
    }

    suspend fun addTrackToPlaylist(
        playlistId: String,
        uri: String,
        snapshotId: String? = null,
        position: Int? = null,
    ): String {
        val normalized = normalizeUri(uri)
        val resolvedSnapshot = resolvePlaylistRevision(playlistId, snapshotId)
        val newSnapshot = nativeGateway().addPlaylistTracks(
            playlistId,
            resolvedSnapshot,
            listOf(normalized),
            position,
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
        val resolvedSnapshot = resolvePlaylistRevision(playlistId, snapshotId)
        val newSnapshot = nativeGateway().removePlaylistTracks(playlistId, resolvedSnapshot, listOf(normalized))
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
            return resolvePlaylistRevision(playlistId, snapshotId)
        }
        val insertBefore = if (toIndex > fromIndex) toIndex + 1 else toIndex
        val resolvedSnapshot = resolvePlaylistRevision(playlistId, snapshotId)
        val newSnapshot = nativeGateway().reorderPlaylistTracks(
            playlistId = playlistId,
            revisionB64 = resolvedSnapshot,
            rangeStart = fromIndex,
            insertBefore = insertBefore,
            rangeLength = 1,
        )
        onPlaylistMutated(playlistId, newSnapshot, invalidateTracks = true)
        return newSnapshot
    }

    suspend fun followPlaylist(playlistId: String) {
        val uri = "spotify:playlist:$playlistId"
        nativeGateway().followPlaylist(uri)
        val bundle = nativeGateway().playlistDetail(playlistId, trackLimit = 1)
        val simple = withResolvedOwner(NativeMetadataAdapter.toPlaylistSimple(bundle.detail))
        libraryRepository.prependPlaylist(simple)
        simple.snapshotId?.takeIf { it.isNotBlank() }?.let { revision ->
            onPlaylistMutated(playlistId, revision)
        }
    }

    suspend fun unfollowPlaylist(playlistId: String) {
        nativeGateway().unfollowPlaylist("spotify:playlist:$playlistId")
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
        val id = nativeMetadata?.takeIf { it.isLoggedIn() }?.sessionUsername()
            ?: webApi.currentUserSuspend().id
        currentUserIdCache = id
        return id
    }

    /** Native rootlist page for library sync. */
    suspend fun nativePlaylistLibraryPage(offset: Int, limit: Int): com.lightphone.spotify.data.webapi.LibraryPage<SpotifyPlaylistSimple> {
        val page = nativeGateway().playlistRootlist(offset, limit)
        val simples = page.playlists.map { NativeMetadataAdapter.toPlaylistSimple(it) }
        val ownerIds = simples.mapNotNull { it.owner?.id?.takeIf(String::isNotBlank) }
        val displayNames = resolveOwnerDisplayNames(ownerIds)
        val resolved = simples.map { simple ->
            val owner = simple.owner ?: return@map simple
            val display = displayNames[owner.id] ?: return@map simple
            simple.copy(owner = owner.copy(displayName = display))
        }
        return com.lightphone.spotify.data.webapi.LibraryPage(
            items = resolved,
            total = page.total.toInt(),
            offset = offset,
        )
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
    suspend fun dailyMixes(): List<SpotifyPlaylistSimple> {
        val now = System.currentTimeMillis()
        dailyMixesCache?.let { (at, items) ->
            if (now - at < CACHE_TTL_MS) return items
        }
        val all = mutableListOf<SpotifyPlaylistSimple>()
        var offset = 0
        while (offset < DAILY_MIXES_SCAN_LIMIT) {
            val page = runCatching {
                nativePlaylistLibraryPage(offset, SpotifyWebApi.LIBRARY_PAGE_LIMIT)
            }.getOrElse { return emptyList() }
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
        ownerDisplayNameCache.clear()
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
        private const val OWNER_DISPLAY_NAME_PARALLELISM = 4
    }

    private fun normalizeUri(uri: String): String = uri.substringBefore('?').trim()

    private fun trackIdFromUri(uri: String): String =
        normalizeUri(uri).substringAfterLast(':')

    private suspend fun resolvePlaylistRevision(playlistId: String, snapshotId: String? = null): String {
        snapshotId?.takeIf { it.isNotBlank() }?.let { return it }
        libraryRepository.getPlaylistSnapshot(playlistId)?.takeIf { it.isNotBlank() }?.let { return it }
        detailCache.indexedSnapshotId(playlistId)?.takeIf { it.isNotBlank() }?.let { return it }
        ephemeralPlaylistCache[playlistId]?.value?.detail?.snapshotId
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val revision = nativeGateway().playlistDetail(playlistId, trackLimit = 1).detail.revisionB64
        if (revision.isNotBlank()) {
            libraryRepository.updatePlaylistSnapshot(playlistId, revision)
        }
        return revision.ifBlank { error("No snapshot_id for playlist") }
    }

    private suspend fun resolveOwnerDisplayNames(ownerIds: Collection<String>): Map<String, String> {
        val unique = ownerIds.filter { it.isNotBlank() }.distinct()
        if (unique.isEmpty()) return emptyMap()

        val result = mutableMapOf<String, String>()
        val pending = mutableListOf<String>()
        for (id in unique) {
            ownerDisplayNameCache[id]?.let { cached ->
                result[id] = cached
            } ?: pending.add(id)
        }
        if (pending.isEmpty()) return result

        val semaphore = Semaphore(OWNER_DISPLAY_NAME_PARALLELISM)
        coroutineScope {
            pending.map { ownerId ->
                async {
                    semaphore.withPermit {
                        ownerId to lookupOwnerDisplayName(ownerId)
                    }
                }
            }.awaitAll().forEach { (ownerId, display) ->
                if (!display.isNullOrBlank() && display != ownerId) {
                    ownerDisplayNameCache[ownerId] = display
                    result[ownerId] = display
                }
            }
        }
        return result
    }

    private suspend fun lookupOwnerDisplayName(ownerId: String): String? {
        runCatching {
            nativeMetadata
                ?.takeIf { it.isLoggedIn() }
                ?.userDisplayName(ownerId)
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()?.let { return it }
        return runCatching {
            val me = runCatching { currentUserIdSuspend() }.getOrNull()
            if (me != null && me == ownerId) {
                webApi.currentUserSuspend().displayName
            } else {
                webApi.userProfile(ownerId).displayName
            }
        }.getOrNull()?.takeIf { !it.isNullOrBlank() }
    }

    private suspend fun resolveOwnerDisplayName(ownerId: String): String {
        if (ownerId.isBlank()) return ownerId
        return resolveOwnerDisplayNames(listOf(ownerId))[ownerId] ?: ownerId
    }

    private suspend fun resolveOwner(owner: SpotifyPlaylistOwner?): SpotifyPlaylistOwner? {
        if (owner == null || owner.id.isBlank()) return owner
        val display = resolveOwnerDisplayName(owner.id)
        return owner.copy(displayName = display)
    }

    private suspend fun withResolvedOwner(simple: SpotifyPlaylistSimple): SpotifyPlaylistSimple {
        val owner = resolveOwner(simple.owner) ?: return simple
        return simple.copy(owner = owner)
    }

    private suspend fun resolvePlaylistDetailOwners(result: PlaylistDetailResult): PlaylistDetailResult {
        val owner = resolveOwner(result.detail.owner)
        return result.copy(detail = result.detail.copy(owner = owner))
    }

    private fun SpotifyPlaylistOwner.resolvedDisplayName(): String? =
        displayName?.takeIf { it.isNotBlank() && it != id }
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
            msg.startsWith("HTTP 401") ->
                "Web API session expired — re-authorize Step 2."
            msg.startsWith("HTTP 403") -> mapWebApi403Error(msg)
            msg.startsWith("HTTP") -> "Can't reach Spotify right now. Try again."
            else -> e.message?.takeIf { it.isNotBlank() }
                ?: "${e::class.simpleName ?: "Error"} — try again."
        }
    }
}

/** Maps native metadata or Web API failures for UI display. */
fun mapRepositoryError(e: Throwable): String {
    if (e is NativeSessionRequiredException ||
        e is com.lightphone.spotify.ffi.SpotifyException ||
        e.message?.contains("not logged in", ignoreCase = true) == true
    ) {
        return mapNativeError(e)
    }
    return mapWebApiError(e)
}

internal fun mapWebApi403Error(message: String): String = when {
    message.contains("/playlists/") && message.contains("/items") ->
        "This playlist's tracks aren't available through the Web API. " +
            "Spotify limits dev apps to playlists you own or collaborate on."
    message.contains("/playlists/") ->
        "This playlist isn't available through the Web API."
    else ->
        "Spotify denied access to this content."
}
