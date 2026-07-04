package com.lightphone.spotify.ui.navigation

import android.net.Uri

sealed class OverlayDestination {
    data object Playing : OverlayDestination()
    data object Queue : OverlayDestination()
    data class SearchInput(val initialQuery: String = "") : OverlayDestination()
    data class SearchResults(val query: String) : OverlayDestination()
    data class Album(val id: String, val title: String = "") : OverlayDestination()
    data class Artist(val id: String) : OverlayDestination()
    data class Playlist(val id: String, val title: String = "") : OverlayDestination()
    data object CreatePlaylist : OverlayDestination()
    data class PlaylistPicker(val trackUri: String) : OverlayDestination()

    fun toRoute(): String = when (this) {
        Playing -> Routes.Playing
        Queue -> Routes.Queue
        is SearchInput -> Routes.searchInput(initialQuery)
        is SearchResults -> Routes.searchResults(query)
        is Album -> Routes.album(id, title)
        is Artist -> Routes.artist(id)
        is Playlist -> Routes.playlist(id, title)
        CreatePlaylist -> Routes.CreatePlaylist
        is PlaylistPicker -> Routes.playlistPicker(trackUri)
    }

    companion object {
        fun fromRoute(route: String?, arguments: Map<String, String?> = emptyMap()): OverlayDestination? {
            val base = route?.substringBefore('?') ?: return null
            return when (base) {
                Routes.Playing -> Playing
                Routes.Queue -> Queue
                "search_input" -> SearchInput(Uri.decode(arguments["query"].orEmpty()))
                "search_results" -> SearchResults(Uri.decode(arguments["query"].orEmpty()))
                "album" -> Album(
                    id = arguments["albumId"].orEmpty(),
                    title = Uri.decode(arguments["title"].orEmpty()),
                )
                "artist" -> Artist(id = arguments["artistId"].orEmpty())
                "playlist" -> Playlist(
                    id = arguments["playlistId"].orEmpty(),
                    title = Uri.decode(arguments["title"].orEmpty()),
                )
                Routes.CreatePlaylist -> CreatePlaylist
                "playlist_picker" -> PlaylistPicker(Uri.decode(arguments["trackUri"].orEmpty()))
                else -> null
            }
        }
    }
}
