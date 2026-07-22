package com.lightphone.spotify.data.tidal

import com.lightphone.spotify.data.SpotifyAlbumDetail
import com.lightphone.spotify.data.SpotifyAlbumSimple
import com.lightphone.spotify.data.SpotifyArtist
import com.lightphone.spotify.data.SpotifyArtistDetail
import com.lightphone.spotify.data.SpotifyImage
import com.lightphone.spotify.data.SpotifyPlaylistDetail
import com.lightphone.spotify.data.SpotifyPlaylistOwner
import com.lightphone.spotify.data.SpotifyPlaylistSimple
import com.lightphone.spotify.data.SpotifyPlaylistTracksRef
import com.lightphone.spotify.data.SpotifySavedAlbum
import com.lightphone.spotify.data.SpotifySavedTrack
import com.lightphone.spotify.data.SpotifyTrack
import com.lightphone.spotify.data.PagedResponse
import kotlinx.serialization.Serializable

/**
 * TIDAL private-API (`api.tidal.com/v1`) DTOs and their mappers into the shared
 * `Spotify*` domain models. Every id carries the `tidal:` URI scheme so routing,
 * caching, and Room stay uniform across backends.
 */

// --- URI helpers -----------------------------------------------------------

internal object TidalUri {
    fun track(id: Long): String = "tidal:track:$id"
    fun album(id: Long): String = "tidal:album:$id"
    fun artist(id: Long): String = "tidal:artist:$id"
    fun playlist(uuid: String): String = "tidal:playlist:$uuid"

    /** Bare id from a `tidal:{type}:{id}` uri (or a plain id). */
    fun rawId(uri: String): String = uri.substringBefore('?').substringAfterLast(':')
}

/** TIDAL cover uuids resolve to CDN URLs; dashes become path separators. */
internal fun tidalImageUrl(cover: String?, size: String = "640x640"): String? {
    if (cover.isNullOrBlank()) return null
    val path = cover.replace('-', '/')
    return "https://resources.tidal.com/images/$path/$size.jpg"
}

private fun images(cover: String?, size: String = "640x640"): List<SpotifyImage> =
    tidalImageUrl(cover, size)?.let { listOf(SpotifyImage(url = it)) } ?: emptyList()

// --- entity DTOs -----------------------------------------------------------

@Serializable
internal data class TidalArtist(
    val id: Long = 0,
    val name: String = "",
    val picture: String? = null,
)

@Serializable
internal data class TidalAlbum(
    val id: Long = 0,
    val title: String = "",
    val cover: String? = null,
    val numberOfTracks: Int = 0,
    val releaseDate: String? = null,
    val artists: List<TidalArtist> = emptyList(),
    val artist: TidalArtist? = null,
)

@Serializable
internal data class TidalTrack(
    val id: Long = 0,
    val title: String = "",
    val duration: Int = 0, // seconds
    val trackNumber: Int = 0,
    val volumeNumber: Int = 1,
    val popularity: Int = 0,
    val artists: List<TidalArtist> = emptyList(),
    val artist: TidalArtist? = null,
    val album: TidalAlbum? = null,
    val allowStreaming: Boolean = true,
)

@Serializable
internal data class TidalPlaylist(
    val uuid: String = "",
    val title: String = "",
    val numberOfTracks: Int = 0,
    val description: String? = null,
    val image: String? = null,
    val squareImage: String? = null,
    val lastUpdated: String? = null,
    val creator: TidalCreator? = null,
    val publicPlaylist: Boolean? = null,
)

@Serializable
internal data class TidalCreator(
    val id: Long? = null,
    val name: String? = null,
)

@Serializable
data class TidalUser(
    val id: Long? = null,
    val username: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
) {
    /** Prefer real name, then username (skip emails / placeholder `"user"`). */
    fun displayName(): String? {
        val full = listOfNotNull(firstName, lastName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifBlank { null }
        if (full != null) return full
        val user = username?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (user.equals("user", ignoreCase = true)) return null
        if ('@' in user) return null
        return user
    }
}

// --- list wrappers ---------------------------------------------------------

@Serializable
internal data class TidalList<T>(
    val items: List<T> = emptyList(),
    val totalNumberOfItems: Int = 0,
)

/** Favorites/playlist rows wrap the entity under `item`/`playlist` plus `created`. */
@Serializable
internal data class TidalFavoriteTrack(val created: String? = null, val item: TidalTrack? = null)

@Serializable
internal data class TidalFavoriteAlbum(val created: String? = null, val item: TidalAlbum? = null)

@Serializable
internal data class TidalPlaylistRow(
    val created: String? = null,
    val playlist: TidalPlaylist? = null,
)

@Serializable
internal data class TidalPlaylistTrackRow(val item: TidalTrack? = null)

@Serializable
internal data class TidalSearchResponse(
    val artists: TidalList<TidalArtist> = TidalList(),
    val albums: TidalList<TidalAlbum> = TidalList(),
    val tracks: TidalList<TidalTrack> = TidalList(),
    val playlists: TidalList<TidalPlaylist> = TidalList(),
)

// --- playback manifest -----------------------------------------------------

@Serializable
internal data class TidalPlaybackInfo(
    val trackId: Long = 0,
    val audioQuality: String = "",
    val manifestMimeType: String = "",
    val manifest: String = "", // base64
)

/** BTS manifest (decoded): direct signed URLs (usually FLAC/AAC). */
@Serializable
internal data class TidalBtsManifest(
    val mimeType: String = "",
    val codecs: String = "",
    val encryptionType: String = "NONE",
    val urls: List<String> = emptyList(),
)

// --- mappers ---------------------------------------------------------------

internal fun TidalArtist.toDomain(): SpotifyArtist = SpotifyArtist(
    id = id.toString(),
    name = name,
    uri = TidalUri.artist(id),
    images = images(picture, size = "320x320"),
)

internal fun TidalAlbum.toDomainSimple(): SpotifyAlbumSimple = SpotifyAlbumSimple(
    id = id.toString(),
    name = title,
    uri = TidalUri.album(id),
    images = images(cover),
    artists = mergedArtists().map { it.toDomain() },
    releaseDate = releaseDate,
)

internal fun TidalAlbum.mergedArtists(): List<TidalArtist> =
    if (artists.isNotEmpty()) artists else listOfNotNull(artist)

internal fun TidalTrack.mergedArtists(): List<TidalArtist> =
    if (artists.isNotEmpty()) artists else listOfNotNull(artist)

internal fun TidalTrack.toDomain(): SpotifyTrack = SpotifyTrack(
    id = id.toString(),
    name = title,
    uri = TidalUri.track(id),
    artists = mergedArtists().map { it.toDomain() },
    album = album?.toDomainSimple(),
    durationMs = duration * 1000L,
    trackNumber = trackNumber,
    discNumber = volumeNumber,
    popularity = popularity,
)

internal fun TidalArtist.toDomainDetail(): SpotifyArtistDetail = SpotifyArtistDetail(
    id = id.toString(),
    name = name,
    uri = TidalUri.artist(id),
    images = images(picture, size = "750x750"),
)

internal fun TidalAlbum.toDomainDetail(tracks: List<TidalTrack>): SpotifyAlbumDetail = SpotifyAlbumDetail(
    id = id.toString(),
    name = title,
    uri = TidalUri.album(id),
    images = images(cover),
    artists = mergedArtists().map { it.toDomain() },
    tracks = PagedResponse(items = tracks.map { it.toDomain() }, total = tracks.size),
)

internal fun TidalPlaylist.toDomainSimple(): SpotifyPlaylistSimple = SpotifyPlaylistSimple(
    id = uuid,
    name = title,
    uri = TidalUri.playlist(uuid),
    images = images(squareImage ?: image),
    owner = creator?.let { SpotifyPlaylistOwner(id = it.id?.toString().orEmpty(), displayName = it.name) },
    snapshotId = lastUpdated,
    tracks = SpotifyPlaylistTracksRef(total = numberOfTracks),
    public = publicPlaylist,
    description = description,
)

internal fun TidalPlaylist.toDomainDetail(): SpotifyPlaylistDetail = SpotifyPlaylistDetail(
    id = uuid,
    name = title,
    uri = TidalUri.playlist(uuid),
    images = images(squareImage ?: image),
    owner = creator?.let { SpotifyPlaylistOwner(id = it.id?.toString().orEmpty(), displayName = it.name) },
    snapshotId = lastUpdated,
    tracks = SpotifyPlaylistTracksRef(total = numberOfTracks),
    public = publicPlaylist,
    description = description,
)

internal fun TidalFavoriteTrack.toSavedTrack(): SpotifySavedTrack =
    SpotifySavedTrack(addedAt = created, track = item?.toDomain())

internal fun TidalFavoriteAlbum.toSavedAlbum(): SpotifySavedAlbum =
    SpotifySavedAlbum(addedAt = created, album = item?.toDomainSimple())
