package com.lightphone.spotify.data.tidal

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for TIDAL -> shared `Spotify*` domain mapping (no network). */
class TidalModelsTest {

    @Test
    fun track_mapsToTidalUriAndMillis() {
        val track = TidalTrack(
            id = 12345,
            title = "Song",
            duration = 180, // seconds
            trackNumber = 3,
            volumeNumber = 2,
            artists = listOf(TidalArtist(id = 1, name = "Artist")),
            album = TidalAlbum(id = 99, title = "Album", cover = "aa-bb-cc"),
        )
        val domain = track.toDomain()
        assertEquals("tidal:track:12345", domain.uri)
        assertEquals("12345", domain.id)
        assertEquals(180_000L, domain.durationMs)
        assertEquals(3, domain.trackNumber)
        assertEquals(2, domain.discNumber)
        assertEquals("tidal:artist:1", domain.artists.first().uri)
        assertEquals("tidal:album:99", domain.album?.uri)
    }

    @Test
    fun coverUuid_expandsToCdnPath() {
        val album = TidalAlbum(id = 7, title = "A", cover = "1e01cdb6-f15d-4d8b-8440-a047976c1cac")
        val url = album.toDomainSimple().images.first().url
        assertEquals(
            "https://resources.tidal.com/images/1e01cdb6/f15d/4d8b/8440/a047976c1cac/640x640.jpg",
            url,
        )
    }

    @Test
    fun album_withoutCover_hasNoImages() {
        val album = TidalAlbum(id = 7, title = "A", cover = null)
        assertTrue(album.toDomainSimple().images.isEmpty())
    }

    @Test
    fun favoriteTrack_mapsAddedAtAndTrack() {
        val fav = TidalFavoriteTrack(
            created = "2024-01-01T00:00:00.000+0000",
            item = TidalTrack(id = 5, title = "T"),
        )
        val saved = fav.toSavedTrack()
        assertEquals("2024-01-01T00:00:00.000+0000", saved.addedAt)
        assertEquals("tidal:track:5", saved.track?.uri)
    }

    @Test
    fun playlist_mapsUuidAndTrackTotal() {
        val playlist = TidalPlaylist(
            uuid = "abc-uuid",
            title = "My Mix",
            numberOfTracks = 42,
            squareImage = "de-ad-be-ef",
            creator = TidalCreator(id = 8, name = "Owner"),
            lastUpdated = "2024-05-05",
        )
        val simple = playlist.toDomainSimple()
        assertEquals("tidal:playlist:abc-uuid", simple.uri)
        assertEquals("abc-uuid", simple.id)
        assertEquals(42, simple.trackCount)
        assertEquals("2024-05-05", simple.snapshotId)
        assertEquals("8", simple.owner?.id)
    }

    @Test
    fun btsManifest_decodesUrls() {
        val json = Json { ignoreUnknownKeys = true }
        val manifest = json.decodeFromString<TidalBtsManifest>(
            """{"mimeType":"audio/flac","codecs":"flac","urls":["https://cdn/track.flac"]}""",
        )
        assertEquals("https://cdn/track.flac", manifest.urls.first())
    }

    @Test
    fun rawId_stripsSchemeAndQuery() {
        assertEquals("999", TidalUri.rawId("tidal:track:999?foo=bar"))
        assertEquals("999", TidalUri.rawId("999"))
    }
}
