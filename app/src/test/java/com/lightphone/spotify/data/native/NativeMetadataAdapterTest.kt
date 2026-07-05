package com.lightphone.spotify.data.native

import com.lightphone.spotify.ffi.AlbumSummaryNative
import com.lightphone.spotify.ffi.ArtistDetailBundle
import com.lightphone.spotify.ffi.EntityInfo
import com.lightphone.spotify.ffi.PlaylistDetailBundle
import com.lightphone.spotify.ffi.PlaylistDetailNative
import com.lightphone.spotify.ffi.PlaylistTrackNative
import com.lightphone.spotify.ffi.TrackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMetadataAdapterTest {

    @Test
    fun toPlaylistDetail_mapsRevisionToSnapshotId() {
        val native = PlaylistDetailNative(
            id = "abc123",
            uri = "spotify:playlist:abc123",
            name = "Test",
            description = "desc",
            ownerId = "user1",
            ownerName = "user1",
            revisionB64 = "cmV2",
            trackCount = 2u,
            imageUrl = "https://i.scdn.co/image/x",
            isPublic = true,
            collaborative = false,
        )
        val detail = NativeMetadataAdapter.toPlaylistDetail(native)
        assertEquals("abc123", detail.id)
        assertEquals("cmV2", detail.snapshotId)
        assertEquals(2, detail.trackCount)
    }

    @Test
    fun toPlaylistDetailResult_marksEditableForOwner() {
        val bundle = PlaylistDetailBundle(
            detail = PlaylistDetailNative(
                id = "p1",
                uri = "spotify:playlist:p1",
                name = "Mix",
                description = "",
                ownerId = "alice",
                ownerName = "alice",
                revisionB64 = "x",
                trackCount = 0u,
                imageUrl = null,
                isPublic = false,
                collaborative = false,
            ),
            tracks = emptyList(),
        )
        val result = NativeMetadataAdapter.toPlaylistDetailResult(bundle, "alice", isInLibrary = true)
        assertTrue(result.isEditable)
        assertTrue(result.isInLibrary)
    }

    @Test
    fun toPlaylistSimple_fromEntityInfo() {
        val entity = EntityInfo(
            entityType = "playlist",
            id = "p2",
            uri = "spotify:playlist:p2",
            name = "Daily Mix 1",
            subtitle = "spotify",
            artUrl = "https://i.scdn.co/image/y",
            trackCount = 42u,
        )
        val simple = NativeMetadataAdapter.toPlaylistSimple(entity)
        assertEquals("p2", simple.id)
        assertEquals("Daily Mix 1", simple.name)
        assertEquals("spotify", simple.owner?.id)
        assertEquals("spotify", simple.owner?.displayName)
        assertEquals(42, simple.trackCount)
    }

    @Test
    fun toArtistDetailResult_mapsTopTracks() {
        val bundle = ArtistDetailBundle(
            id = "a1",
            name = "Artist",
            imageUrl = null,
            genres = listOf("rock"),
            topTracks = listOf(
                TrackInfo(
                    uri = "spotify:track:t1",
                    title = "Song",
                    artists = "Artist",
                    album = "Album",
                    durationMs = 180_000,
                    artUrl = null,
                ),
            ),
            albums = listOf(
                AlbumSummaryNative(
                    id = "al1",
                    name = "LP",
                    uri = "spotify:album:al1",
                    imageUrl = null,
                    albumType = "album",
                ),
            ),
        )
        val result = NativeMetadataAdapter.toArtistDetailResult(bundle)
        assertEquals("Artist", result.artist.name)
        assertEquals(1, result.topTracks.size)
        assertEquals("Song", result.topTracks.first().name)
        assertEquals(1, result.albums.size)
        assertEquals("Artist", result.albums.first().artists.first().name)
    }
}
