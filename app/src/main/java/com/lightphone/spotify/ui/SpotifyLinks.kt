package com.lightphone.spotify.ui

/** Shareable https URL for clipboard (prefers open.spotify.com over spotify: URIs). */
fun spotifyShareUrl(uri: String, id: String, type: String): String {
    if (uri.startsWith("https://open.spotify.com/")) return uri
    val fromUri = uri.removePrefix("spotify:").split(":")
    if (fromUri.size >= 2 && fromUri[1].isNotBlank()) {
        return "https://open.spotify.com/${fromUri[0]}/${fromUri[1]}"
    }
    if (id.isNotBlank()) return "https://open.spotify.com/$type/$id"
    return uri
}

fun trackIdFromUri(uri: String): String =
    uri.removePrefix("spotify:track:").substringBefore("?").trim()
