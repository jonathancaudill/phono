package com.lightphone.spotify.playback.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStatesTest {
    @Test
    fun media3CompatibleInts() {
        // Must match androidx.media3.exoplayer.offline.Download STATE_* values.
        assertEquals(0, DownloadStates.QUEUED)
        assertEquals(1, DownloadStates.STOPPED)
        assertEquals(2, DownloadStates.DOWNLOADING)
        assertEquals(3, DownloadStates.COMPLETED)
        assertEquals(4, DownloadStates.FAILED)
        assertEquals(5, DownloadStates.REMOVING)
        assertEquals(7, DownloadStates.RESTARTING)
    }

    @Test
    fun skipAndActive() {
        assertTrue(DownloadStates.shouldSkipEnqueue(DownloadStates.COMPLETED))
        assertTrue(DownloadStates.isActive(DownloadStates.QUEUED))
        assertFalse(DownloadStates.isActive(DownloadStates.FAILED))
        assertFalse(DownloadStates.shouldSkipEnqueue(DownloadStates.STOPPED))
        assertFalse(DownloadStates.shouldSkipEnqueue(DownloadStates.FAILED))
    }

    @Test
    fun collectionUiTreatsPartialAsNotDownloading() {
        assertEquals(
            CollectionDownloadUi.Complete,
            DownloadStates.collectionUi(10, completed = 10, inProgress = 0, failed = 0, stopped = 0),
        )
        assertEquals(
            CollectionDownloadUi.Downloading,
            DownloadStates.collectionUi(10, completed = 4, inProgress = 2, failed = 0, stopped = 0),
        )
        assertEquals(
            CollectionDownloadUi.Paused,
            DownloadStates.collectionUi(10, completed = 4, inProgress = 0, failed = 0, stopped = 6),
        )
        assertEquals(
            CollectionDownloadUi.Partial,
            DownloadStates.collectionUi(10, completed = 7, inProgress = 0, failed = 3, stopped = 0),
        )
        assertEquals(
            CollectionDownloadUi.None,
            DownloadStates.collectionUi(10, completed = 0, inProgress = 0, failed = 0, stopped = 0),
        )
    }
}
