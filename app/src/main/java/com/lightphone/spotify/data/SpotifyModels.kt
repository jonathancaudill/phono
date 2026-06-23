package com.lightphone.spotify.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SpotifyImage(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class SpotifyArtist(
    val id: String = "",
    val name: String = "",
    val uri: String = "",
    val images: List<SpotifyImage> = emptyList(),
    val popularity: Int = 0,
)

@Serializable
data class SpotifyAlbumSimple(
    val id: String = "",
    val name: String = "",
    val uri: String = "",
    val images: List<SpotifyImage> = emptyList(),
    val artists: List<SpotifyArtist> = emptyList(),
    @SerialName("album_type") val albumType: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val popularity: Int = 0,
)

@Serializable
data class SpotifyTrack(
    val id: String = "",
    val name: String = "",
    val uri: String = "",
    val artists: List<SpotifyArtist> = emptyList(),
    val album: SpotifyAlbumSimple? = null,
    @SerialName("duration_ms") val durationMs: Long = 0,
    @SerialName("track_number") val trackNumber: Int = 0,
    @SerialName("disc_number") val discNumber: Int = 1,
    val popularity: Int = 0,
)

@Serializable
data class SpotifySavedAlbum(
    @SerialName("added_at") val addedAt: String? = null,
    val album: SpotifyAlbumSimple,
)

@Serializable
data class SpotifySavedTrack(
    @SerialName("added_at") val addedAt: String? = null,
    val track: SpotifyTrack,
)

@Serializable
data class PagedResponse<T>(
    val items: List<T> = emptyList(),
    val next: String? = null,
    val total: Int = 0,
)

/** Search API pages may contain null slots for unavailable results. */
@Serializable
data class SearchPagedResponse<T>(
    val items: List<T?> = emptyList(),
    val next: String? = null,
    val total: Int = 0,
)

@Serializable
data class SpotifyAlbumDetail(
    val id: String = "",
    val name: String = "",
    val uri: String = "",
    val images: List<SpotifyImage> = emptyList(),
    val artists: List<SpotifyArtist> = emptyList(),
    val tracks: PagedResponse<SpotifyTrack> = PagedResponse(),
)

@Serializable
data class SpotifyArtistDetail(
    val id: String = "",
    val name: String = "",
    val uri: String = "",
    val images: List<SpotifyImage> = emptyList(),
    val genres: List<String> = emptyList(),
    val followers: Followers? = null,
)

@Serializable
data class Followers(
    val total: Int = 0,
)

@Serializable
data class TopTracksResponse(
    val tracks: List<SpotifyTrack> = emptyList(),
)

@Serializable
data class SpotifySearchResults(
    val tracks: SearchPagedResponse<SpotifyTrack>? = null,
    val albums: SearchPagedResponse<SpotifyAlbumSimple>? = null,
    val artists: SearchPagedResponse<SpotifyArtist>? = null,
    val playlists: SearchPagedResponse<SpotifyPlaylistSimple>? = null,
)

@Serializable
data class SpotifyPlaylistSimple(
    val id: String = "",
    val name: String = "",
    val uri: String = "",
    val images: List<SpotifyImage> = emptyList(),
    val owner: SpotifyPlaylistOwner? = null,
)

@Serializable
data class SpotifyPlaylistOwner(
    val id: String = "",
    @SerialName("display_name") val displayName: String? = null,
)

sealed class SearchResultItem {
    abstract val id: String
    abstract val title: String
    abstract val subtitle: String
    abstract val imageUrl: String?
    abstract val uri: String

    data class Track(val track: SpotifyTrack) : SearchResultItem() {
        override val id = track.id
        override val title = track.name
        override val subtitle = "Song • ${track.artists.joinToString { it.name }}"
        override val imageUrl = track.album?.images?.firstOrNull()?.url
        override val uri = track.uri
    }

    data class Album(val album: SpotifyAlbumSimple) : SearchResultItem() {
        override val id = album.id
        override val title = album.name
        override val subtitle = "Album • ${album.artists.joinToString { it.name }}"
        override val imageUrl = album.images.firstOrNull()?.url
        override val uri = album.uri
    }

    data class Artist(val artist: SpotifyArtist) : SearchResultItem() {
        override val id = artist.id
        override val title = artist.name
        override val subtitle = "Artist"
        override val imageUrl = artist.images.firstOrNull()?.url
        override val uri = artist.uri
    }

    data class Playlist(val playlist: SpotifyPlaylistSimple) : SearchResultItem() {
        override val id = playlist.id
        override val title = playlist.name
        override val subtitle = "Playlist • ${playlist.owner?.displayName ?: "Playlist"}"
        override val imageUrl = playlist.images.firstOrNull()?.url
        override val uri = playlist.uri
    }
}

enum class SearchFilter(val label: String) {
    All("All"),
    Tracks("Songs"),
    Artists("Artists"),
    Albums("Albums"),
    Playlists("Playlists"),
}

data class SearchResults(
    val query: String,
    val artists: List<SpotifyArtist> = emptyList(),
    val albums: List<SpotifyAlbumSimple> = emptyList(),
    val tracks: List<SpotifyTrack> = emptyList(),
    val playlists: List<SpotifyPlaylistSimple> = emptyList(),
    val topResult: SearchResultItem? = null,
    val rankedItems: List<SearchResultItem> = emptyList(),
) {
    fun isEmpty(): Boolean =
        topResult == null &&
            rankedItems.isEmpty() &&
            artists.isEmpty() &&
            albums.isEmpty() &&
            tracks.isEmpty() &&
            playlists.isEmpty()

    /** Top result + interleaved remainder for the All filter. */
    fun allItems(): List<SearchResultItem> = buildList {
        topResult?.let { add(it) }
        addAll(rankedItems)
    }

    fun itemsForFilter(filter: SearchFilter): Pair<SearchResultItem?, List<SearchResultItem>> =
        when (filter) {
            SearchFilter.All -> topResult to rankedItems
            SearchFilter.Tracks -> null to tracks.take(10).map { SearchResultItem.Track(it) }
            SearchFilter.Artists -> null to artists.take(10).map { SearchResultItem.Artist(it) }
            SearchFilter.Albums -> null to albums.take(10).map { SearchResultItem.Album(it) }
            SearchFilter.Playlists -> null to playlists.take(10).map { SearchResultItem.Playlist(it) }
        }
}

fun SpotifyTrack.toMetadata(): TrackMetadata = TrackMetadata(
    uri = uri,
    title = name,
    artists = artists.joinToString { it.name },
    album = album?.name ?: "",
    durationMs = durationMs,
    artUrl = album?.images?.firstOrNull()?.url,
    albumId = album?.id,
    artistIds = artists.map { it.id },
)
