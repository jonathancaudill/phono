package com.lightphone.spotify.data.local

import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.InvocationTargetException

/**
 * [LibrarySync.kt]'s `computeBatchNextOffset` is a private top-level helper that
 * fixes a bug where empty API pages returned mid-batch (e.g. deleted/unavailable
 * items) would stall [next_offset] forever, wedging the background library fill.
 * It is invoked via reflection since it is intentionally file-private.
 */
class LibrarySyncNextOffsetTest {

    private fun invoke(
        startOffset: Int,
        remoteTotal: Int,
        pages: List<Pair<Int, Int>>,
        pageSize: Int,
    ): Int {
        val clazz = Class.forName("com.lightphone.spotify.data.local.LibrarySyncKt")
        val method = clazz.getDeclaredMethod(
            "computeBatchNextOffset",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            List::class.java,
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return try {
            method.invoke(null, startOffset, remoteTotal, pages, pageSize) as Int
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Test
    fun allPagesFull_advancesByTotalItemsFetched() {
        val result = invoke(
            startOffset = 0,
            remoteTotal = 100,
            pages = listOf(0 to 20, 20 to 20, 40 to 20),
            pageSize = 20,
        )

        assertEquals(60, result)
    }

    @Test
    fun emptyPageMidBatch_advancesByPageSizeInsteadOfStalling() {
        // Regression test: an empty page at offset 20 must not stall next_offset at 20 forever.
        val result = invoke(
            startOffset = 0,
            remoteTotal = 100,
            pages = listOf(0 to 20, 20 to 0, 40 to 20),
            pageSize = 20,
        )

        assertEquals(60, result)
    }

    @Test
    fun consecutiveEmptyPages_advanceToRemoteTotalWithoutStalling() {
        val result = invoke(
            startOffset = 0,
            remoteTotal = 45,
            pages = listOf(0 to 0, 20 to 0, 40 to 0),
            pageSize = 20,
        )

        assertEquals(45, result)
    }

    @Test
    fun trailingEmptyPageNearEnd_clampsToRemoteTotal() {
        val result = invoke(
            startOffset = 80,
            remoteTotal = 90,
            pages = listOf(80 to 0),
            pageSize = 20,
        )

        assertEquals(90, result)
    }

    @Test
    fun outOfOrderPages_areSortedBeforeAdvancing() {
        val result = invoke(
            startOffset = 0,
            remoteTotal = 60,
            pages = listOf(40 to 20, 0 to 20, 20 to 20),
            pageSize = 20,
        )

        assertEquals(60, result)
    }

    @Test
    fun pageBeforeCurrentOffset_isSkippedAndDoesNotMoveBackward() {
        val result = invoke(
            startOffset = 40,
            remoteTotal = 100,
            pages = listOf(0 to 20, 40 to 20),
            pageSize = 20,
        )

        assertEquals(60, result)
    }

    @Test
    fun noPages_returnsStartOffsetUnchanged() {
        val result = invoke(startOffset = 30, remoteTotal = 100, pages = emptyList(), pageSize = 20)

        assertEquals(30, result)
    }

    @Test
    fun partialFinalPage_advancesByActualItemCountNotPageSize() {
        // A short final page (fewer items than pageSize) should advance by the
        // actual item count returned, not overshoot remoteTotal.
        val result = invoke(
            startOffset = 40,
            remoteTotal = 47,
            pages = listOf(40 to 7),
            pageSize = 20,
        )

        assertEquals(47, result)
    }
}