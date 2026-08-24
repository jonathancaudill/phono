package com.lightphone.spotify.playback.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueMoveIndexTest {
    @Test
    fun prefersHintWhenItStillMatches() {
        val uris = listOf("a", "b", "c")
        assertEquals(2, QueueMoveIndex.resolve(uris, "c", hint = 2))
    }

    @Test
    fun findsUriWhenHintIsStale() {
        // UI still thinks the row is at 0; live list already swapped.
        val uris = listOf("b", "a", "c")
        assertEquals(1, QueueMoveIndex.resolve(uris, "a", hint = 0))
    }

    @Test
    fun staleHintOnDuplicatePrefersHintOnlyIfItMatches() {
        val uris = listOf("a", "b", "a")
        assertEquals(2, QueueMoveIndex.resolve(uris, "a", hint = 2))
        assertEquals(0, QueueMoveIndex.resolve(uris, "a", hint = 0))
        // Hint 1 is "b"; fall back to first "a".
        assertEquals(0, QueueMoveIndex.resolve(uris, "a", hint = 1))
    }

    @Test
    fun missingUriReturnsNull() {
        assertNull(QueueMoveIndex.resolve(listOf("a", "b"), "z", hint = 0))
        assertNull(QueueMoveIndex.resolve(emptyList(), "a", hint = 0))
    }
}
