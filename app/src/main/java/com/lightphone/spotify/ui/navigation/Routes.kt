package com.lightphone.spotify.ui.navigation

object Routes {
    const val Login = "login"
    const val Main = "main"
    const val Playing = "playing"
    const val SearchResults = "search_results/{query}"
    const val Album = "album/{albumId}?title={title}"
    const val Artist = "artist/{artistId}"

    fun searchResults(query: String) = "search_results/${java.net.URLEncoder.encode(query, Charsets.UTF_8.name())}"
    fun album(albumId: String, title: String = "") =
        "album/$albumId?title=${java.net.URLEncoder.encode(title, Charsets.UTF_8.name())}"
    fun artist(artistId: String) = "artist/$artistId"
}
