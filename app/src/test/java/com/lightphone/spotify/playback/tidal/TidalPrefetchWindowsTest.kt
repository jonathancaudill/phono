package com.lightphone.spotify.playback.tidal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalPrefetchWindowsTest {
    @Test
    fun playStartResolveRange_includesPrevAndAhead() {
        // start=5, ahead=3 → 4..8
        assertEquals(4..8, TidalPrefetchWindows.playStartResolveRange(5, 20, 3))
    }

    @Test
    fun playStartResolveRange_clampsToBounds() {
        assertEquals(0..2, TidalPrefetchWindows.playStartResolveRange(0, 2, 3))
        // start at end: prev..end
        assertEquals(9..10, TidalPrefetchWindows.playStartResolveRange(10, 10, 2))
    }

    @Test
    fun playStartResolveRange_capsAheadAt3() {
        assertEquals(4..8, TidalPrefetchWindows.playStartResolveRange(5, 100, 99))
    }

    @Test
    fun streamCacheBytes_isAtLeast256MiB() {
        assertTrue(TidalMediaCache.STREAM_CACHE_BYTES >= 256L * 1024L * 1024L)
    }
}
