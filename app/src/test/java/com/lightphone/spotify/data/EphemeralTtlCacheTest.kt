package com.lightphone.spotify.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EphemeralTtlCacheTest {

    @Test
    fun get_returnsPutValue_untilTtl() {
        var now = 1_000L
        val cache = EphemeralTtlCache<String, String>(
            ttlMs = 100L,
            maxSize = 4,
            nowMs = { now },
        )
        cache.put("a", "one")
        assertEquals("one", cache.get("a"))
        now = 1_099L
        assertEquals("one", cache.get("a"))
        now = 1_100L
        assertNull(cache.get("a"))
        assertEquals(0, cache.size)
    }

    @Test
    fun put_doesNotGrowPastCap() {
        val cache = EphemeralTtlCache<String, String>(
            ttlMs = 10_000L,
            maxSize = 2,
            nowMs = { 0L },
        )
        cache.put("a", "1")
        cache.put("b", "2")
        cache.put("c", "3")
        assertEquals(2, cache.size)
        assertNull(cache.get("a"))
        assertEquals("2", cache.get("b"))
        assertEquals("3", cache.get("c"))
    }

    @Test
    fun put_updateExistingAtCap_doesNotEvictNeighbor() {
        val cache = EphemeralTtlCache<String, String>(
            ttlMs = 10_000L,
            maxSize = 2,
            nowMs = { 0L },
        )
        cache.put("a", "1")
        cache.put("b", "2")
        cache.put("a", "1b")
        assertEquals(2, cache.size)
        assertEquals("1b", cache.get("a"))
        assertEquals("2", cache.get("b"))
    }

    @Test
    fun get_promotesLru_soLeastUsedIsEvicted() {
        var now = 0L
        val cache = EphemeralTtlCache<String, String>(
            ttlMs = 10_000L,
            maxSize = 2,
            nowMs = { now },
        )
        cache.put("a", "1")
        now = 1L
        cache.put("b", "2")
        now = 2L
        cache.get("a")
        now = 3L
        cache.put("c", "3")
        assertNull(cache.get("b"))
        assertEquals("1", cache.get("a"))
        assertEquals("3", cache.get("c"))
    }

    @Test
    fun put_sweepsExpiredWithoutWaitingForCap() {
        var now = 0L
        val cache = EphemeralTtlCache<String, String>(
            ttlMs = 10L,
            maxSize = 8,
            nowMs = { now },
        )
        cache.put("a", "1")
        cache.put("b", "2")
        now = 10L
        cache.put("c", "3")
        assertEquals(1, cache.size)
        assertNull(cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals("3", cache.get("c"))
    }

    @Test
    fun clear_dropsEverything() {
        val cache = EphemeralTtlCache<String, String>(
            ttlMs = 10_000L,
            maxSize = 4,
            nowMs = { 0L },
        )
        cache.put("a", "1")
        cache.put("b", "2")
        cache.clear()
        assertEquals(0, cache.size)
        assertNull(cache.get("a"))
        assertTrue(cache.size == 0)
    }

    @Test
    fun remove_dropsOneKey() {
        val cache = EphemeralTtlCache<String, String>(
            ttlMs = 10_000L,
            maxSize = 4,
            nowMs = { 0L },
        )
        cache.put("a", "1")
        cache.put("b", "2")
        cache.remove("a")
        assertNull(cache.get("a"))
        assertEquals("2", cache.get("b"))
        assertEquals(1, cache.size)
    }
}
