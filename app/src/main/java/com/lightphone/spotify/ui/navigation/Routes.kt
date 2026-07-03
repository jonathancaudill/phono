package com.lightphone.spotify.ui.navigation

import android.net.Uri

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

    fun searchResults(query: String) = "search_results/${Uri.encode(query)}"
    fun album(albumId: String, title: String = "") =
        "album/$albumId?title=${Uri.encode(title)}"
    fun artist(artistId: String) = "artist/$artistId"
    fun playlist(playlistId: String, title: String = "") =
        "playlist/$playlistId?title=${Uri.encode(title)}"
    fun playlistPicker(trackUri: String) =
        "playlist_picker/${Uri.encode(trackUri)}"
}
