package com.lightphone.spotify.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreHoriz
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
    Settings(
        route = "settings",
        label = "Settings",
        icon = Icons.Filled.MoreHoriz,
    ),
}

val DefaultPhonoTabs = listOf(
    PhonoTab.Liked,
    PhonoTab.Albums,
    PhonoTab.Playlists,
    PhonoTab.Search,
    PhonoTab.Settings,
)
