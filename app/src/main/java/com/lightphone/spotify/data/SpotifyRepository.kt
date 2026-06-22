package com.lightphone.spotify.data

import com.lightphone.spotify.ffi.AlbumDetailInfo
import com.lightphone.spotify.ffi.ArtistDetailInfo
import com.lightphone.spotify.ffi.EntityInfo
import com.lightphone.spotify.ffi.LibrespotEngine
import com.lightphone.spotify.ffi.SavedAlbumInfo
import com.lightphone.spotify.ffi.TrackInfo
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * UI-facing track model. Playback uses librespot; browse/library metadata uses
 * the internal spclient stack (context-resolve, your-library, Login5) in Rust.
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

/** Cached library metadata via librespot spclient (Rust FFI). */
class SpotifyRepository(
    private val engine: LibrespotEngine,
) {
    private val searchCache = ConcurrentHashMap<String, Pair<Long, SearchResults>>()
    private var savedAlbumsCache: Pair<Long, List<SpotifySavedAlbum>>? = null
    private var likedTracksCache: Pair<Long, List<TrackMetadata>>? = null

    fun invalidateSavedAlbums() {
        savedAlbumsCache = null
    }

    fun invalidateLikedTracks() {
        likedTracksCache = null
    }

    fun savedAlbums(limit: Int = 500): List<SpotifySavedAlbum> {
        val now = System.currentTimeMillis()
        savedAlbumsCache?.let { (at, items) ->
            if (now - at < CACHE_TTL_MS) return items
        }
        val items = engine.savedAlbums(limit.toUInt()).map { it.toSpotifySavedAlbum() }
        savedAlbumsCache = now to items
        return items
    }

    fun likedTracks(limit: Int = 500): List<TrackMetadata> {
        val now = System.currentTimeMillis()
        likedTracksCache?.let { (at, items) ->
            if (now - at < CACHE_TTL_MS) return items
        }
        val items = engine.likedTracks(limit.toUInt()).map { it.toMetadata() }
        likedTracksCache = now to items
        return items
    }

    fun albumDetail(albumId: String): AlbumDetailResult {
        val detail = engine.albumDetail(albumId)
        return detail.toAlbumDetailResult()
    }

    fun artistDetail(artistId: String): ArtistDetailResult {
        val detail = engine.artistDetail(artistId)
        return detail.toArtistDetailResult()
    }

    fun search(query: String, limitPerType: Int = 5): SearchResults {
        val key = query.trim()
        if (key.isEmpty()) return SearchResults(query = "")
        val now = System.currentTimeMillis()
        searchCache[key]?.let { (at, items) ->
            if (now - at < CACHE_TTL_MS) return items
        }
        val limit = (limitPerType * 4).coerceIn(4, 50)
        val entities = engine.searchCatalog(key, limit.toUInt())
        val items = entities.toSearchResults(key)
        searchCache[key] = now to items
        return items
    }

    fun isTrackSaved(uri: String): Boolean =
        runCatching { engine.isTrackSaved(uri) }.getOrDefault(false)

    fun saveTrack(uri: String) {
        engine.saveTrack(uri)
        invalidateLikedTracks()
    }

    fun removeTrack(uri: String) {
        engine.removeTrack(uri)
        invalidateLikedTracks()
    }

    fun saveAlbum(albumId: String) {
        engine.saveAlbum(albumId)
        invalidateSavedAlbums()
    }

    fun removeAlbum(albumId: String) {
        engine.removeAlbum(albumId)
        invalidateSavedAlbums()
    }

    fun albumTracks(albumId: String): List<TrackMetadata> =
        engine.albumDetail(albumId).tracks.map { it.toMetadata() }

    companion object {
        private const val CACHE_TTL_MS = 5 * 60_000L
    }
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

private fun images(url: String?): List<SpotifyImage> =
    url?.let { listOf(SpotifyImage(url = it)) } ?: emptyList()

private fun SavedAlbumInfo.toSpotifySavedAlbum(): SpotifySavedAlbum = SpotifySavedAlbum(
    addedAt = addedAtMs?.let { Instant.ofEpochMilli(it).toString() },
    album = album.toSpotifyAlbumSimple(),
)

private fun EntityInfo.toSpotifyAlbumSimple(): SpotifyAlbumSimple = SpotifyAlbumSimple(
    id = id,
    name = name,
    uri = uri,
    images = images(artUrl),
    artists = subtitle.split(", ").filter { it.isNotBlank() }.map { SpotifyArtist(name = it) },
)

private fun EntityInfo.toSpotifyArtist(): SpotifyArtist = SpotifyArtist(
    id = id,
    name = name,
    uri = uri,
    images = images(artUrl),
)

private fun EntityInfo.toSpotifyTrack(): SpotifyTrack = SpotifyTrack(
    id = id,
    name = name,
    uri = uri,
    artists = subtitle.split(", ").filter { it.isNotBlank() }.map { SpotifyArtist(name = it) },
    album = null,
    durationMs = 0,
)

private fun EntityInfo.toSpotifyPlaylist(): SpotifyPlaylistSimple = SpotifyPlaylistSimple(
    id = id,
    name = name,
    uri = uri,
    images = images(artUrl),
)

private fun AlbumDetailInfo.toAlbumDetailResult(): AlbumDetailResult = AlbumDetailResult(
    album = SpotifyAlbumDetail(
        id = album.id,
        name = album.name,
        uri = album.uri,
        images = images(album.artUrl),
        artists = album.subtitle.split(", ").filter { it.isNotBlank() }.map { SpotifyArtist(name = it) },
        tracks = PagedResponse(items = tracks.map { track ->
            SpotifyTrack(
                id = track.uri.substringAfterLast(':'),
                name = track.title,
                uri = track.uri,
                artists = track.artists.split(", ").filter { it.isNotBlank() }.map { SpotifyArtist(name = it) },
                album = SpotifyAlbumSimple(
                    id = album.id,
                    name = track.album.ifBlank { album.name },
                    uri = album.uri,
                    images = images(album.artUrl),
                ),
                durationMs = track.durationMs,
            )
        }),
    ),
    isSaved = isSaved,
)

private fun ArtistDetailInfo.toArtistDetailResult(): ArtistDetailResult = ArtistDetailResult(
    artist = SpotifyArtistDetail(
        id = artist.id,
        name = artist.name,
        uri = artist.uri,
        images = images(artist.artUrl),
    ),
    topTracks = topTracks.map { track ->
        SpotifyTrack(
            id = track.uri.substringAfterLast(':'),
            name = track.title,
            uri = track.uri,
            artists = track.artists.split(", ").filter { it.isNotBlank() }.map { SpotifyArtist(name = it) },
            album = SpotifyAlbumSimple(name = track.album),
            durationMs = track.durationMs,
        )
    },
    albums = albums.map { it.toSpotifyAlbumSimple() },
)

private fun List<EntityInfo>.toSearchResults(query: String): SearchResults {
    val artists = mutableListOf<SpotifyArtist>()
    val albums = mutableListOf<SpotifyAlbumSimple>()
    val tracks = mutableListOf<SpotifyTrack>()
    val playlists = mutableListOf<SpotifyPlaylistSimple>()
    for (entity in this) {
        when (entity.entityType) {
            "artist" -> artists.add(entity.toSpotifyArtist())
            "album" -> albums.add(entity.toSpotifyAlbumSimple())
            "track" -> tracks.add(entity.toSpotifyTrack())
            "playlist" -> playlists.add(entity.toSpotifyPlaylist())
        }
    }
    return SearchResults(
        query = query,
        artists = artists,
        albums = albums,
        tracks = tracks,
        playlists = playlists,
    )
}
