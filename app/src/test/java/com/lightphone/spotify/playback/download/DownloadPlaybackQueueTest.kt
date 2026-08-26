package com.lightphone.spotify.playback.download

import com.lightphone.spotify.data.local.DownloadedTrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPlaybackQueueTest {
    @Test
    fun tappingATrackQueuesTheRestOfTheCollection() {
        val tracks = listOf(
            pin("spotify:track:a"),
            pin("spotify:track:b"),
            pin("spotify:track:c"),
            pin("spotify:track:d"),
        )

        val start = DownloadPlaybackQueue.fromCollection(tracks, "spotify:track:b")!!

        assertEquals(
            listOf(
                "spotify:track:a",
                "spotify:track:b",
                "spotify:track:c",
                "spotify:track:d",
            ),
            start.tracks.map { it.uri },
        )
        assertEquals(1, start.startIndex)
        val remaining = start.tracks.drop(start.startIndex + 1).map { it.uri }
        assertEquals(listOf("spotify:track:c", "spotify:track:d"), remaining)
    }

    @Test
    fun skipsIncompleteTracksButKeepsAlbumOrder() {
        val tracks = listOf(
            pin("spotify:track:a"),
            pin("spotify:track:b", state = DownloadStates.DOWNLOADING),
            pin("spotify:track:c"),
            pin("spotify:track:d", state = DownloadStates.FAILED),
            pin("spotify:track:e"),
        )

        val start = DownloadPlaybackQueue.fromCollection(tracks, "spotify:track:a")!!

        assertEquals(
            listOf("spotify:track:a", "spotify:track:c", "spotify:track:e"),
            start.tracks.map { it.uri },
        )
        assertEquals(0, start.startIndex)
    }

    @Test
    fun tappingLaterKeepsEarlierTracksInContextForShuffle() {
        val tracks = listOf(
            pin("spotify:track:a"),
            pin("spotify:track:b"),
            pin("spotify:track:c"),
        )

        val start = DownloadPlaybackQueue.fromCollection(tracks, "spotify:track:c")!!

        assertEquals(2, start.startIndex)
        assertEquals("spotify:track:a", start.tracks[0].uri)
        assertEquals("spotify:track:c", start.tracks[start.startIndex].uri)
    }

    @Test
    fun tappingAnIncompleteTrackDoesNotStartPlayback() {
        val tracks = listOf(
            pin("spotify:track:a"),
            pin("spotify:track:b", state = DownloadStates.QUEUED),
        )
        assertNull(DownloadPlaybackQueue.fromCollection(tracks, "spotify:track:b"))
    }

    @Test
    fun unknownUriReturnsNull() {
        assertNull(
            DownloadPlaybackQueue.fromCollection(
                listOf(pin("spotify:track:a")),
                "spotify:track:missing",
            ),
        )
    }

    @Test
    fun emptyCollectionReturnsNull() {
        assertNull(DownloadPlaybackQueue.fromCollection(emptyList(), "spotify:track:a"))
    }

    @Test
    fun remainingContextIsWideEnoughForShuffleAndRepeat() {
        val tracks = (1..8).map { pin("spotify:track:$it") }
        val start = DownloadPlaybackQueue.fromCollection(tracks, "spotify:track:5")!!
        assertEquals(8, start.tracks.size)
        assertEquals(4, start.startIndex)
        assertTrue(start.tracks.size - 1 > start.startIndex)
    }
}

private fun pin(
    uri: String,
    state: Int = DownloadStates.COMPLETED,
): DownloadedTrackEntity = DownloadedTrackEntity(
    uri = uri,
    title = uri.substringAfterLast(':'),
    artists = "Artist",
    album = "Album",
    art_url = null,
    quality = "high",
    state = state,
    bytes = 1_024L,
    updated_at = 0L,
    duration_ms = 180_000L,
)
