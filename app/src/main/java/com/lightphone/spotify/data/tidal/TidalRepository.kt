package com.lightphone.spotify.data.tidal

import com.lightphone.spotify.data.AlbumDetailResult
import com.lightphone.spotify.data.ArtistDetailResult
import com.lightphone.spotify.data.MusicRepository
import com.lightphone.spotify.data.PlaylistDetailResult
import com.lightphone.spotify.data.SearchRanking
import com.lightphone.spotify.data.SearchResults
import com.lightphone.spotify.data.SpotifyAlbumSimple
import com.lightphone.spotify.data.SpotifyPlaylistDetail
import com.lightphone.spotify.data.SpotifyPlaylistOwner
import com.lightphone.spotify.data.SpotifyPlaylistSimple
import com.lightphone.spotify.data.SpotifyPlaylistTrackItem
import com.lightphone.spotify.data.SpotifySavedAlbum
import com.lightphone.spotify.data.TrackMetadata
import com.lightphone.spotify.data.local.LibraryRepository
import com.lightphone.spotify.data.local.PlaylistEntity
import com.lightphone.spotify.data.toMetadata
import com.lightphone.spotify.data.toPlaylistSimple
import com.lightphone.spotify.data.webapi.LibraryPage
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * TIDAL implementation of [MusicRepository]. Maps TIDAL private-API responses into
 * the shared `Spotify*` domain models (see [TidalModels]) so the Compose UI is
 * backend-agnostic. Library lists are cached in the same Room store used by the
 * Spotify backend (via [LibraryRepository]); TIDAL's ETag is used as the playlist
 * concurrency token in place of Spotify's `snapshot_id`.
 */
class TidalRepository(
    private val api: TidalApiClient,
    private val auth: TidalAuth,
    private val libraryRepository: LibraryRepository,
) : MusicRepository {

    private val searchCache = ConcurrentHashMap<String, SearchResults>()

    override fun hasPlaybackCredsWithoutLiveSession(): Boolean = false

    // --- detail reads -------------------------------------------------------

    override suspend fun albumDetail(albumId: String): AlbumDetailResult = withContext(Dispatchers.IO) {
        val id = TidalUri.rawId(albumId)
        val album = api.album(id)
        AlbumDetailResult(album = album, isSaved = libraryRepository.isSavedAlbumCached(id))
    }

    override fun artistDetail(artistId: String): ArtistDetailResult = runBlocking(Dispatchers.IO) {
        val id = TidalUri.rawId(artistId)
        ArtistDetailResult(
            artist = api.artist(id),
            topTracks = api.artistTopTracks(id, limit = 10),
            albums = api.artistAlbums(id, limit = 50),
        )
    }

    override suspend fun playlistDetail(playlistId: String, trackLimit: Int): PlaylistDetailResult =
        withContext(Dispatchers.IO) {
            val uuid = TidalUri.rawId(playlistId)
            val detail = api.playlist(uuid)
            val tracks = api.playlistTracks(uuid, trackLimit.coerceIn(1, 500))
                .map { SpotifyPlaylistTrackItem(track = it) }
            val userId = auth.userId().orEmpty()
            val isEditable = detail.owner?.id == userId && userId.isNotBlank()
            val isInLibrary = libraryRepository.playlistsSnapshot().any { it.playlist_id == uuid }
            val resolvedOwner = resolveOwner(detail.owner)
            resolvedOwner?.displayName
                ?.takeIf { it.isNotBlank() && it != resolvedOwner.id }
                ?.let { libraryRepository.updatePlaylistOwnerName(uuid, it) }
            PlaylistDetailResult(
                detail = detail.copy(owner = resolvedOwner),
                tracks = tracks,
                currentUserId = userId,
                isEditable = isEditable,
                isInLibrary = isInLibrary,
            )
        }

    override fun playlistTracks(playlistId: String, limit: Int): List<TrackMetadata> =
        runBlocking(Dispatchers.IO) {
            api.playlistTracks(TidalUri.rawId(playlistId), limit.coerceIn(1, 500)).map { it.toMetadata() }
        }

    override fun albumTracks(albumId: String): List<TrackMetadata> = runBlocking(Dispatchers.IO) {
        api.albumTracks(TidalUri.rawId(albumId)).map { it.toMetadata() }
    }

    override fun trackMetadataForUri(uri: String): TrackMetadata? {
        val id = TidalUri.rawId(uri)
        if (id.isBlank()) return null
        return runCatching { runBlocking(Dispatchers.IO) { api.track(id).toMetadata() } }.getOrNull()
    }

    // --- search / discovery -------------------------------------------------

    override fun search(query: String, limitPerType: Int): SearchResults {
        val key = query.trim()
        if (key.isEmpty()) return SearchResults(query = "")
        searchCache[key]?.let { return it }
        val response = runBlocking(Dispatchers.IO) { api.search(key, limitPerType) }
        val base = SearchResults(
            query = key,
            artists = response.artists?.items.orEmpty().filterNotNull(),
            albums = response.albums?.items.orEmpty().filterNotNull(),
            tracks = response.tracks?.items.orEmpty().filterNotNull(),
            playlists = response.playlists?.items.orEmpty().filterNotNull(),
        )
        val ranked = SearchRanking.rank(key, base)
        val result = base.copy(topResult = ranked.topResult, rankedItems = ranked.rankedItems)
        if (searchCache.size > SEARCH_CACHE_CAP) searchCache.clear()
        searchCache[key] = result
        return result
    }

    /** TIDAL editorial mixes require the /pages surface; unsupported for now. */
    override suspend fun dailyMixes(): List<SpotifyPlaylistSimple> = emptyList()

    // --- user / identity ----------------------------------------------------

    override fun currentUserId(): String = auth.userId().orEmpty()

    override suspend fun currentUserIdSuspend(): String = auth.userId().orEmpty()

    // --- saves / library membership ----------------------------------------

    override fun isTrackSaved(uri: String): Boolean =
        runBlocking { libraryRepository.isLikedTrackCached(normalize(uri)) }

    override suspend fun isSavedAlbumCached(albumId: String): Boolean =
        libraryRepository.isSavedAlbumCached(TidalUri.rawId(albumId))

    override suspend fun saveTrack(uri: String) = withContext(Dispatchers.IO) {
        val id = TidalUri.rawId(uri)
        api.addFavoriteTrack(id)
        val meta = trackMetadataForUri(uri)
            ?: throw IllegalStateException("Could not load track metadata after save")
        libraryRepository.prependLikedTrack(meta)
    }

    override suspend fun removeTrack(uri: String) = withContext(Dispatchers.IO) {
        api.removeFavoriteTrack(TidalUri.rawId(uri))
        libraryRepository.removeLikedTrack(normalize(uri))
    }

    override suspend fun saveAlbum(albumId: String) = withContext(Dispatchers.IO) {
        val id = TidalUri.rawId(albumId)
        api.addFavoriteAlbum(id)
        val detail = api.album(id)
        libraryRepository.prependSavedAlbum(
            SpotifySavedAlbum(
                addedAt = Instant.now().toString(),
                album = SpotifyAlbumSimple(
                    id = detail.id,
                    name = detail.name,
                    uri = detail.uri,
                    images = detail.images,
                    artists = detail.artists,
                ),
            ),
        )
    }

    override suspend fun removeAlbum(albumId: String) = withContext(Dispatchers.IO) {
        val id = TidalUri.rawId(albumId)
        api.removeFavoriteAlbum(id)
        libraryRepository.removeSavedAlbum(id)
    }

    // --- playlist edits (ETag is the concurrency token) --------------------

    override suspend fun createPlaylist(name: String, isPublic: Boolean): SpotifyPlaylistSimple =
        withContext(Dispatchers.IO) {
            val detail = api.createPlaylist(name.trim(), description = null)
            val simple = detail.toPlaylistSimple()
            libraryRepository.prependPlaylist(simple)
            simple
        }

    override suspend fun renamePlaylist(playlistId: String, name: String): SpotifyPlaylistDetail =
        withContext(Dispatchers.IO) {
            val uuid = TidalUri.rawId(playlistId)
            val trimmed = name.trim()
            api.renamePlaylist(uuid, trimmed, etag = null)
            libraryRepository.updatePlaylistName(uuid, trimmed)
            api.playlist(uuid)
        }

    override suspend fun addTrackToPlaylist(
        playlistId: String,
        uri: String,
        snapshotId: String?,
        position: Int?,
    ): String = withContext(Dispatchers.IO) {
        val uuid = TidalUri.rawId(playlistId)
        val newEtag = api.addPlaylistTracks(uuid, listOf(TidalUri.rawId(uri)), etag = null, toIndex = position)
        libraryRepository.updatePlaylistSnapshot(uuid, newEtag)
        newEtag
    }

    override suspend fun removeTrackFromPlaylist(
        playlistId: String,
        uri: String,
        snapshotId: String?,
    ): String = withContext(Dispatchers.IO) {
        val uuid = TidalUri.rawId(playlistId)
        val targetUri = normalize(uri)
        val index = api.playlistTracks(uuid, limit = 500)
            .indexOfFirst { normalize(it.uri) == targetUri }
        if (index < 0) return@withContext libraryRepository.getPlaylistSnapshot(uuid).orEmpty()
        val newEtag = api.removePlaylistItem(uuid, index, etag = null)
        libraryRepository.updatePlaylistSnapshot(uuid, newEtag)
        newEtag
    }

    override suspend fun reorderPlaylistTrack(
        playlistId: String,
        fromIndex: Int,
        toIndex: Int,
        snapshotId: String?,
    ): String = withContext(Dispatchers.IO) {
        val uuid = TidalUri.rawId(playlistId)
        if (fromIndex == toIndex) return@withContext libraryRepository.getPlaylistSnapshot(uuid).orEmpty()
        val newEtag = api.movePlaylistItem(uuid, fromIndex, toIndex, etag = null)
        libraryRepository.updatePlaylistSnapshot(uuid, newEtag)
        newEtag
    }

    override suspend fun followPlaylist(playlistId: String) = withContext(Dispatchers.IO) {
        val uuid = TidalUri.rawId(playlistId)
        api.addFavoritePlaylist(uuid)
        libraryRepository.prependPlaylist(api.playlist(uuid).toPlaylistSimple())
    }

    override suspend fun unfollowPlaylist(playlistId: String) = withContext(Dispatchers.IO) {
        val uuid = TidalUri.rawId(playlistId)
        api.removeFavoritePlaylist(uuid)
        libraryRepository.removePlaylist(uuid)
    }

    override suspend fun editablePlaylists(userId: String?): List<PlaylistEntity> {
        val resolved = userId ?: currentUserIdSuspend()
        return libraryRepository.playlistsSnapshot().filter { it.owner_id == resolved }
    }

    override suspend fun playlistsContainingTrack(
        trackUri: String,
        playlistIds: List<String>,
    ): Set<String> = emptySet()

    override suspend fun syncPlaylistUriIndex() {
        // TIDAL playlist membership indexing is not supported; no-op.
    }

    // --- library paging (drives LibraryRepository sync) ---------------------

    override suspend fun playlistLibraryPage(
        offset: Int,
        limit: Int,
    ): LibraryPage<SpotifyPlaylistSimple> = withContext(Dispatchers.IO) {
        val page = api.playlistsPage(offset, limit)
        val me = auth.userId().orEmpty()
        val resolved = page.items.map { simple ->
            val owner = simple.owner ?: return@map simple
            val display = TidalPlaylistOwners.resolve(owner.id, owner.displayName, me)
            simple.copy(owner = owner.copy(displayName = display)).also {
                if (display.isNotBlank() && display != owner.id) {
                    libraryRepository.updatePlaylistOwnerName(simple.id, display)
                }
            }
        }
        page.copy(items = resolved)
    }

    // --- cache lifecycle ----------------------------------------------------

    override suspend fun clearLibraryCache() {
        libraryRepository.clearAll()
    }

    override fun clearSessionCaches() {
        searchCache.clear()
    }

    private fun resolveOwner(owner: SpotifyPlaylistOwner?): SpotifyPlaylistOwner? {
        if (owner == null || owner.id.isBlank()) return owner
        val me = auth.userId().orEmpty()
        return owner.copy(displayName = TidalPlaylistOwners.resolve(owner.id, owner.displayName, me))
    }

    private fun normalize(uri: String): String = uri.substringBefore('?').trim()

    companion object {
        private const val SEARCH_CACHE_CAP = 25
    }
}
