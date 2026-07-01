package com.lightphone.spotify.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lightphone.spotify.ui.theme.PhonoColors
import com.lightphone.spotify.ui.theme.n

/** Icon row: vertical padding n(11) + icon n(48) + vertical padding n(11). */
val PhonoNavbarBarHeight: Dp = n(70)

val PhonoNavbarOfflineStripHeight: Dp = n(18)

fun phonoNavbarTotalHeight(showOfflineStrip: Boolean): Dp =
    PhonoNavbarBarHeight + if (showOfflineStrip) PhonoNavbarOfflineStripHeight else 0.dp

@Composable
fun PhonoNavbar(
    tabs: List<PhonoTab>,
    currentTab: PhonoTab,
    onTabSelected: (PhonoTab) -> Unit,
    statusMessage: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PhonoColors.Background)
                .padding(horizontal = n(20), vertical = n(11)),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val active = tab == currentTab
                Icon(
                    tab.icon,
                    contentDescription = tab.label,
                    tint = if (active) PhonoColors.Foreground else PhonoColors.InactiveTab,
                    modifier = Modifier
                        .size(n(48))
                        .tap { onTabSelected(tab) },
                )
            }
        }
        if (statusMessage != null) {
            OfflineStrip(statusMessage)
        }
    }
}

@Composable
private fun OfflineStrip(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(n(18))
            .background(PhonoColors.OfflineStripBg),
        contentAlignment = Alignment.Center,
    ) {
        StyledText(message, size = 12, color = PhonoColors.OfflineStripFg)
    }
}

enum class PhonoTab(val route: String, val label: String, val icon: ImageVector) {
    Liked("liked", "Liked Songs", Icons.Default.Favorite),
    Albums("albums", "Albums", Icons.Default.Album),
    Playlists("playlists", "Playlists", Icons.AutoMirrored.Filled.PlaylistPlay),
    Search("search", "Search", Icons.Default.Search),
    Settings("settings", "Settings", Icons.Default.MoreHoriz),
}

val DefaultPhonoTabs = listOf(
    PhonoTab.Liked,
    PhonoTab.Albums,
    PhonoTab.Playlists,
    PhonoTab.Search,
    PhonoTab.Settings,
)
