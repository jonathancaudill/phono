package com.lightphone.spotify.data

import com.lightphone.spotify.data.local.LibraryRepository
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

/** Metadata via Spotify Web API. */
class SpotifyRepository(
    private val webApi: SpotifyWebApi,
    private val libraryRepository: LibraryRepository,
) {
    private val searchCache = ConcurrentHashMap<String, Pair<Long, SearchResults>>()
    private var dailyMixesCache: Pair<Long, List<SpotifyPlaylistSimple>>? = null

    fun albumDetail(albumId: String): AlbumDetailResult {
        val album = webApi.album(albumId)
        val uri = album.uri.ifBlank { "spotify:album:$albumId" }
        val isSaved = webApi.libraryContains(listOf(uri)).firstOrNull() ?: false
        return AlbumDetailResult(album = album, isSaved = isSaved)
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
        searchCache[key]?.let { (at, items) ->
            if (now - at < CACHE_TTL_MS) return items
        }
        val apiResults = webApi.search(key, limitPerType)
        val items = apiResults.toSearchResults(key)
        searchCache[key] = now to items
        return items
    }

    fun playlistTracks(playlistId: String, limit: Int = 100): List<TrackMetadata> =
        webApi.playlistItems(playlistId, limit).map { it.toMetadata() }

    fun isTrackSaved(uri: String): Boolean =
        runCatching {
            webApi.libraryContains(listOf(normalizeUri(uri))).firstOrNull() ?: false
        }.getOrDefault(false)

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
    }

    suspend fun removeAlbum(albumId: String) {
        webApi.removeLibrary(listOf("spotify:album:$albumId"))
        libraryRepository.removeSavedAlbum(albumId)
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
        val items = webApi.myPlaylists(50).filter { playlist ->
            playlist.name.contains("Daily Mix", ignoreCase = true) ||
                playlist.name.contains("Discover Weekly", ignoreCase = true) ||
                playlist.name.contains("Release Radar", ignoreCase = true)
        }
        dailyMixesCache = now to items
        return items
    }

    suspend fun clearLibraryCache() {
        libraryRepository.clearAll()
    }

    companion object {
        private const val CACHE_TTL_MS = 5 * 60_000L
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
