package com.lightphone.spotify.playback.download

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPacingTest {
    @Test
    fun detectsHttp429AndResourceExhausted() {
        assertTrue(DownloadPacing.looksRateLimited("HTTP 429: rate limited after 4 retries"))
        assertTrue(DownloadPacing.looksRateLimited("rate limited for at least another 3 seconds"))
        assertTrue(DownloadPacing.looksRateLimited("resource exhausted: too many requests"))
        assertTrue(DownloadPacing.isRateLimited(IOException("CDN 429 Retry-After=20")))
        assertTrue(
            DownloadPacing.isRateLimited(
                RuntimeException("download failed", IOException("Too Many Requests")),
            ),
        )
    }

    @Test
    fun ignoresUnrelatedFailures() {
        assertFalse(DownloadPacing.looksRateLimited("audio key timeout"))
        assertFalse(DownloadPacing.looksRateLimited("HTTP 403 forbidden"))
        assertFalse(DownloadPacing.isRateLimited(IOException("no playable file")))
    }

    @Test
    fun hardSetGapsMatchResearchNote() {
        assertEquals(2_500L, DownloadPacing.TRACK_GAP_MIN_MS)
        assertEquals(5_000L, DownloadPacing.TRACK_GAP_MAX_MS)
        assertEquals(20_000L, DownloadPacing.RATE_LIMIT_COOLDOWN_MS)
        assertEquals(8, DownloadPacing.RATE_LIMIT_RETRY_MAX)
    }
}
