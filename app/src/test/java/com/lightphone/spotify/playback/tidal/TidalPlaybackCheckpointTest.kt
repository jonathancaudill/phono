package com.lightphone.spotify.playback.tidal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class TidalPlaybackCheckpointTest {
    @Test
    fun roundTripPreservesQueueAndPosition() {
        val dir = newDir()
        val snap = TidalPlaybackCheckpoint.Snapshot(
            mediaIds = listOf("tidal:track:1", "tidal:track:2", "tidal:track:3"),
            currentIndex = 1,
            positionMs = 12_000L,
            manualIds = listOf("tidal:track:9"),
            contextLabel = "Album",
            shuffle = true,
            repeat = "CONTEXT",
        )
        TidalPlaybackCheckpoint.save(dir, snap)
        val restored = TidalPlaybackCheckpoint.loadIfFresh(dir)
        assertEquals(snap.mediaIds, restored!!.mediaIds)
        assertEquals(1, restored.currentIndex)
        assertEquals(12_000L, restored.positionMs)
        assertEquals(listOf("tidal:track:9"), restored.manualIds)
        assertEquals("Album", restored.contextLabel)
        assertTrue(restored.shuffle)
        assertEquals("CONTEXT", restored.repeat)
        TidalPlaybackCheckpoint.delete(dir)
        assertFalse(TidalPlaybackCheckpoint.file(dir).exists())
        dir.deleteRecursively()
    }

    @Test
    fun expiredCheckpointIsDiscarded() {
        val dir = newDir()
        TidalPlaybackCheckpoint.file(dir).writeText(
            """{"savedAtUnixMs":0,"mediaIds":["tidal:track:1"],"currentIndex":0,"positionMs":0}""",
        )
        assertNull(TidalPlaybackCheckpoint.loadIfFresh(dir))
        assertFalse(TidalPlaybackCheckpoint.file(dir).exists())
        dir.deleteRecursively()
    }

    @Test
    fun emptyMediaIdsDeletesFile() {
        val dir = newDir()
        TidalPlaybackCheckpoint.save(
            dir,
            TidalPlaybackCheckpoint.Snapshot(
                mediaIds = emptyList(),
                currentIndex = 0,
                positionMs = 0L,
                manualIds = emptyList(),
                contextLabel = null,
                shuffle = false,
                repeat = "OFF",
            ),
        )
        assertFalse(TidalPlaybackCheckpoint.file(dir).exists())
        dir.deleteRecursively()
    }

    @Test
    fun missingFileReturnsNull() {
        val dir = newDir()
        assertNull(TidalPlaybackCheckpoint.loadIfFresh(dir))
        dir.deleteRecursively()
    }

    private fun newDir(): File =
        createTempDirectory("phono_tidal_ckpt_").toFile()
}
