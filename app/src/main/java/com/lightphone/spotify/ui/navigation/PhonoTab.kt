package com.lightphone.spotify.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons

enum class PhonoTab(
    val route: String,
    val label: String,
    val icon: ImageVector? = null,
    val lightIcon: LightIconConfiguration? = null,
) {
    Liked(
        route = "liked",
        label = "Liked Songs",
        icon = Icons.Filled.Favorite,
    ),
    Albums(
        route = "albums",
        label = "Albums",
        icon = Icons.Filled.Album,
    ),
    Playlists(
        route = "playlists",
        label = "Playlists",
        lightIcon = LightIcons.LIST,
    ),
    Search(
        route = "search",
        label = "Search",
        icon = Icons.Filled.Search,
    ),
    Downloads(
        route = "downloads",
        label = "Downloads",
        lightIcon = LightIcons.DOWNLOAD_ARROW,
    ),
}

/** Library tabs always present. [PhonoTab.Downloads] when offline downloads are supported. */
fun phonoTabs(includeDownloads: Boolean): List<PhonoTab> = buildList {
    add(PhonoTab.Liked)
    add(PhonoTab.Albums)
    add(PhonoTab.Playlists)
    add(PhonoTab.Search)
    if (includeDownloads) add(PhonoTab.Downloads)
}

val DefaultPhonoTabs = phonoTabs(includeDownloads = false)
