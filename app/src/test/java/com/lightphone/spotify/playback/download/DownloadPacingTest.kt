package com.lightphone.spotify.playback.download

import java.io.IOException
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPacingTest {
    @Test
    fun detectsHttp429AndAudioKey() {
        assertTrue(DownloadPacing.looksRateLimited("HTTP 429: rate limited after 4 retries"))
        assertTrue(DownloadPacing.looksRateLimited("rate limited for at least another 3 seconds"))
        assertTrue(DownloadPacing.looksRateLimited("resource exhausted: too many requests"))
        assertTrue(DownloadPacing.looksRateLimited("audio key: timeout"))
        assertTrue(DownloadPacing.isRateLimited(IOException("CDN 429 Retry-After=20")))
        assertTrue(
            DownloadPacing.isRateLimited(
                RuntimeException("download failed", IOException("Too Many Requests")),
            ),
        )
    }

    @Test
    fun ignoresUnrelatedFailures() {
        assertFalse(DownloadPacing.looksRateLimited("HTTP 403 forbidden"))
        assertFalse(DownloadPacing.isRateLimited(IOException("no playable file")))
        assertTrue(DownloadPacing.looksPermanentFailure("no playable file for uri"))
        assertTrue(DownloadPacing.isPermanentFailure(IOException("HTTP 403 forbidden")))
    }

    @Test
    fun jitterStaysBetweenBaseAndOneAndAHalf() {
        val rng = Random(42)
        repeat(200) {
            val wait = DownloadPacing.jitter(10_000L, rng)
            assertTrue("wait=$wait", wait in 10_000L..15_000L)
        }
        assertEquals(0L, DownloadPacing.jitter(0L, rng))
    }

    @Test
    fun fastModeIgnoresQueueDepth() {
        assertEquals(
            DownloadPacing.SMALL_GAP_BASE_MS,
            DownloadPacing.keyGapBaseMs(DownloadPaceMode.FAST, remaining = 200, durationMs = 210_000L),
        )
    }

    @Test
    fun balancedGapsFollowRemainingWork() {
        assertEquals(
            DownloadPacing.SMALL_GAP_BASE_MS,
            DownloadPacing.keyGapBaseMs(DownloadPaceMode.BALANCED, remaining = 10, durationMs = 210_000L),
        )
        assertEquals(
            DownloadPacing.MEDIUM_GAP_BASE_MS,
            DownloadPacing.keyGapBaseMs(DownloadPaceMode.BALANCED, remaining = 25, durationMs = 210_000L),
        )
        // 0.12 * 210s = 25.2s, clamped to 12–40s
        assertEquals(
            25_200L,
            DownloadPacing.keyGapBaseMs(DownloadPaceMode.BALANCED, remaining = 80, durationMs = 210_000L),
        )
        assertEquals(
            DownloadPacing.LARGE_GAP_MIN_MS,
            DownloadPacing.keyGapBaseMs(DownloadPaceMode.BALANCED, remaining = 80, durationMs = 0L),
        )
        assertEquals(
            DownloadPacing.LARGE_GAP_MAX_MS,
            DownloadPacing.keyGapBaseMs(DownloadPaceMode.BALANCED, remaining = 80, durationMs = 600_000L),
        )
    }

    @Test
    fun carefulLongPauseOnlyOnCarefulLargeQueues() {
        val takePause = object : Random() {
            override fun nextBits(bitCount: Int): Int = 0
            override fun nextDouble(): Double = 0.0
        }
        val skipPause = object : Random() {
            override fun nextBits(bitCount: Int): Int = 0
            override fun nextDouble(): Double = 0.99
        }
        assertNull(DownloadPacing.carefulLongPauseBaseMs(DownloadPaceMode.FAST, 200, takePause))
        assertNull(DownloadPacing.carefulLongPauseBaseMs(DownloadPaceMode.BALANCED, 200, takePause))
        assertNull(DownloadPacing.carefulLongPauseBaseMs(DownloadPaceMode.CAREFUL, 5, takePause))
        assertEquals(
            DownloadPacing.CAREFUL_LONG_PAUSE_LARGE_BASE_MS,
            DownloadPacing.carefulLongPauseBaseMs(DownloadPaceMode.CAREFUL, 200, takePause),
        )
        assertNull(DownloadPacing.carefulLongPauseBaseMs(DownloadPaceMode.CAREFUL, 200, skipPause))
    }

    @Test
    fun circuitWaitHonorsRetryAfterAsFloor() {
        val rng = Random(7)
        repeat(50) {
            val wait = DownloadPacing.circuitWaitMs(1, retryAfterMs = 30_000L, rng)
            assertTrue("wait=$wait", wait in 30_000L..45_000L)
        }
    }

    @Test
    fun parseRetryAfterSecondsAndMillis() {
        assertEquals(20_000L, DownloadPacing.parseRetryAfterMs("CDN 429 Retry-After=20"))
        assertEquals(3_000L, DownloadPacing.parseRetryAfterMs("rate limited for at least another 3 seconds"))
        assertEquals(20_000L, DownloadPacing.parseRetryAfterMs("Retry-After=20000"))
        assertNull(DownloadPacing.parseRetryAfterMs("audio key timeout"))
    }
}
