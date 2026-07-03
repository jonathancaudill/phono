package com.lightphone.spotify.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryDateScrubOverlayTest {
    private val heightPx = 1000f

    @Test
    fun scrubZoneBounds_middle65Percent() {
        val (top, bottom) = scrubZoneBounds(heightPx)
        assertEquals(175f, top, 0.01f)
        assertEquals(825f, bottom, 0.01f)
    }

    @Test
    fun indexAtZoneTop_returnsZero() {
        val (top, _) = scrubZoneBounds(heightPx)
        assertEquals(0, indexForVerticalPosition(top, count = 12, heightPx = heightPx))
    }

    @Test
    fun indexAtZoneBottom_returnsLastIndex() {
        val (_, bottom) = scrubZoneBounds(heightPx)
        assertEquals(11, indexForVerticalPosition(bottom, count = 12, heightPx = heightPx))
    }

    @Test
    fun indexAboveZone_clampsToZero() {
        assertEquals(0, indexForVerticalPosition(0f, count = 12, heightPx = heightPx))
    }

    @Test
    fun indexBelowZone_clampsToLast() {
        assertEquals(11, indexForVerticalPosition(heightPx, count = 12, heightPx = heightPx))
    }

    @Test
    fun indexMidZone_snapsToNearest() {
        val (top, bottom) = scrubZoneBounds(heightPx)
        val mid = (top + bottom) / 2f
        assertEquals(6, indexForVerticalPosition(mid, count = 12, heightPx = heightPx))
    }

    @Test
    fun indexSingleItem_alwaysZero() {
        assertEquals(0, indexForVerticalPosition(500f, count = 1, heightPx = heightPx))
    }

    @Test
    fun visibleItems_midList_showsSevenItemsCentered() {
        val items = scrubWheelVisibleItems(selectedIndex = 6, count = 12)
        assertEquals(
            listOf(
                ScrubWheelItem(3, 0),
                ScrubWheelItem(4, 1),
                ScrubWheelItem(5, 2),
                ScrubWheelItem(6, 3),
                ScrubWheelItem(7, 4),
                ScrubWheelItem(8, 5),
                ScrubWheelItem(9, 6),
            ),
            items,
        )
    }

    @Test
    fun visibleItems_startEdge_clampsWithoutNegativeIndices() {
        val items = scrubWheelVisibleItems(selectedIndex = 0, count = 12)
        assertEquals(
            listOf(
                ScrubWheelItem(0, 3),
                ScrubWheelItem(1, 4),
                ScrubWheelItem(2, 5),
                ScrubWheelItem(3, 6),
            ),
            items,
        )
    }

    @Test
    fun visibleItems_endEdge_clampsWithoutOverflow() {
        val items = scrubWheelVisibleItems(selectedIndex = 11, count = 12)
        assertEquals(
            listOf(
                ScrubWheelItem(8, 0),
                ScrubWheelItem(9, 1),
                ScrubWheelItem(10, 2),
                ScrubWheelItem(11, 3),
            ),
            items,
        )
    }

    @Test
    fun visibleItems_smallList_selectedCenteredWhenPossible() {
        val items = scrubWheelVisibleItems(selectedIndex = 1, count = 3)
        assertEquals(
            listOf(
                ScrubWheelItem(0, 2),
                ScrubWheelItem(1, 3),
                ScrubWheelItem(2, 4),
            ),
            items,
        )
    }

    @Test
    fun visibleItems_emptyCount_returnsEmpty() {
        assertTrue(scrubWheelVisibleItems(selectedIndex = 0, count = 0).isEmpty())
    }
}
