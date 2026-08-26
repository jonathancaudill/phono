package com.lightphone.spotify.playback.tidal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalQueueSpliceTest {
    @Test
    fun emptyManualsKeepsFullContextAndStartIndex() {
        val context = listOf("a", "b", "c")
        val plan = TidalQueueSplice.plan(context, startIndex = 1, upcomingManualIds = emptyList())
        assertEquals(context, plan.playUris)
        assertEquals(1, plan.startIndex)
        assertTrue(plan.manualIds.isEmpty())
    }

    @Test
    fun manualsSitBetweenNewNowPlayingAndContextTail() {
        val context = listOf("a", "b", "c", "d")
        val manuals = listOf("q1", "q2")
        val plan = TidalQueueSplice.plan(context, startIndex = 1, upcomingManualIds = manuals)
        assertEquals(listOf("b", "q1", "q2", "c", "d"), plan.playUris)
        assertEquals(0, plan.startIndex)
        assertEquals(manuals, plan.manualIds)
    }

    @Test
    fun currentlyPlayingManualIsNotInUpcomingList() {
        // Caller is responsible for passing only upcoming manuals (play_index+1).
        val context = listOf("x", "y")
        val plan = TidalQueueSplice.plan(context, 0, upcomingManualIds = listOf("later"))
        assertEquals(listOf("x", "later", "y"), plan.playUris)
        assertEquals(listOf("later"), plan.manualIds)
    }

    @Test
    fun emptyContextIsNoop() {
        val plan = TidalQueueSplice.plan(emptyList(), 0, listOf("q"))
        assertTrue(plan.playUris.isEmpty())
        assertEquals(0, plan.startIndex)
    }

    @Test
    fun startIndexIsClamped() {
        val plan = TidalQueueSplice.plan(listOf("a", "b"), startIndex = 99, upcomingManualIds = emptyList())
        assertEquals(1, plan.startIndex)
        assertEquals(listOf("a", "b"), plan.playUris)
    }
}
