package com.lightphone.spotify.data.backend

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionUriTest {
    @Test
    fun prefersExistingUri() {
        assertEquals(
            "spotify:album:abc",
            collectionUri(BackendChoice.SPOTIFY, CollectionKind.Album, "xyz", "spotify:album:abc"),
        )
    }

    @Test
    fun buildsSpotifyAlbum() {
        assertEquals(
            "spotify:album:xyz",
            collectionUri(BackendChoice.SPOTIFY, CollectionKind.Album, "xyz"),
        )
    }

    @Test
    fun buildsTidalPlaylist() {
        assertEquals(
            "tidal:playlist:uuid",
            collectionUri(BackendChoice.TIDAL, CollectionKind.Playlist, "uuid"),
        )
    }
}
