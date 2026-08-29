package com.lightphone.spotify.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayReloadTest {

    @Test
    fun skip_whenSameIdAlreadyLoaded() {
        assertTrue(
            shouldSkipOverlayReload(
                requestedId = "a1",
                currentRequestedId = "a1",
                hasPayload = true,
                loading = false,
                error = null,
            ),
        )
    }

    @Test
    fun skip_whenSameIdAlreadyInFlight() {
        assertTrue(
            shouldSkipOverlayReload(
                requestedId = "a1",
                currentRequestedId = "a1",
                hasPayload = false,
                loading = true,
                error = null,
            ),
        )
    }

    @Test
    fun refetch_whenIdChanged() {
        assertFalse(
            shouldSkipOverlayReload(
                requestedId = "b",
                currentRequestedId = "a",
                hasPayload = true,
                loading = false,
                error = null,
            ),
        )
    }

    @Test
    fun refetch_whenSameIdFailed() {
        assertFalse(
            shouldSkipOverlayReload(
                requestedId = "a1",
                currentRequestedId = "a1",
                hasPayload = false,
                loading = false,
                error = "nope",
            ),
        )
    }

    @Test
    fun skipSearch_whenResultsForQueryExist() {
        assertTrue(
            shouldSkipSearchReload(
                query = "radiohead",
                resultsQuery = "radiohead",
                hasResults = true,
            ),
        )
    }

    @Test
    fun refetchSearch_whenQueryChanged() {
        assertFalse(
            shouldSkipSearchReload(
                query = "bjork",
                resultsQuery = "radiohead",
                hasResults = true,
            ),
        )
    }

    @Test
    fun refetchSearch_whenNoResults() {
        assertFalse(
            shouldSkipSearchReload(
                query = "radiohead",
                resultsQuery = "radiohead",
                hasResults = false,
            ),
        )
    }
}
