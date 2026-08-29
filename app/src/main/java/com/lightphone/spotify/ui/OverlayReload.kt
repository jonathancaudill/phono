package com.lightphone.spotify.ui

/**
 * Overlay destinations are disposed on push and recomposed on pop.
 * Skip a refetch when this destination's payload is already in the ViewModel slot.
 */
internal fun shouldSkipOverlayReload(
    requestedId: String,
    currentRequestedId: String?,
    hasPayload: Boolean,
    loading: Boolean,
    error: String?,
): Boolean {
    if (currentRequestedId != requestedId) return false
    if (loading) return true
    return hasPayload && error == null
}

internal fun shouldSkipSearchReload(
    query: String,
    resultsQuery: String?,
    hasResults: Boolean,
): Boolean = resultsQuery == query && hasResults
