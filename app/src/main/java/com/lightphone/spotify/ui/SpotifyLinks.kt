package com.lightphone.spotify.ui

/**
 * Shareable https URL for clipboard. Provider-aware: `spotify:` URIs resolve to
 * open.spotify.com, `tidal:` URIs to tidal.com/browse.
 */
fun spotifyShareUrl(uri: String, id: String, type: String): String {
    if (uri.startsWith("https://open.spotify.com/") || uri.startsWith("https://tidal.com/")) return uri
    if (uri.startsWith("tidal:")) {
        val parts = uri.removePrefix("tidal:").split(":")
        if (parts.size >= 2 && parts[1].isNotBlank()) {
            return "https://tidal.com/browse/${parts[0]}/${parts[1]}"
        }
        if (id.isNotBlank()) return "https://tidal.com/browse/$type/$id"
        return uri
    }
    val fromUri = uri.removePrefix("spotify:").split(":")
    if (fromUri.size >= 2 && fromUri[1].isNotBlank()) {
        return "https://open.spotify.com/${fromUri[0]}/${fromUri[1]}"
    }
    if (id.isNotBlank()) return "https://open.spotify.com/$type/$id"
    return uri
}

/** Bare track id from a `spotify:track:{id}` or `tidal:track:{id}` URI. */
fun trackIdFromUri(uri: String): String =
    uri.substringBefore("?").substringAfterLast(":").trim()
