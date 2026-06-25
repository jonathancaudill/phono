package com.lightphone.spotify.ui.navigation

object Routes {
    const val Login = "login"
    const val Main = "main"
    const val Playing = "playing"
    const val Queue = "queue"
    const val SearchResults = "search_results/{query}"
    const val Album = "album/{albumId}?title={title}"
    const val Artist = "artist/{artistId}"
    const val Playlist = "playlist/{playlistId}?title={title}"
    const val CreatePlaylist = "create_playlist"
    const val PlaylistPicker = "playlist_picker/{trackUri}"

    fun searchResults(query: String) = "search_results/${java.net.URLEncoder.encode(query, Charsets.UTF_8.name())}"
    fun album(albumId: String, title: String = "") =
        "album/$albumId?title=${java.net.URLEncoder.encode(title, Charsets.UTF_8.name())}"
    fun artist(artistId: String) = "artist/$artistId"
    fun playlist(playlistId: String, title: String = "") =
        "playlist/$playlistId?title=${java.net.URLEncoder.encode(title, Charsets.UTF_8.name())}"
    fun playlistPicker(trackUri: String) =
        "playlist_picker/${java.net.URLEncoder.encode(trackUri, Charsets.UTF_8.name())}"
}
