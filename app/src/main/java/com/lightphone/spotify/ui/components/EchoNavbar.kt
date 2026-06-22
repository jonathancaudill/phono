package com.lightphone.spotify.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.lightphone.spotify.ui.theme.EchoColors

@Composable
fun EchoNavbar(
    tabs: List<EchoTab>,
    currentTab: EchoTab,
    onTabSelected: (EchoTab) -> Unit,
    statusMessage: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(EchoColors.Background)
                .padding(horizontal = 20.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val active = tab == currentTab
                IconButton(onClick = { onTabSelected(tab) }) {
                    Icon(
                        tab.icon,
                        contentDescription = tab.label,
                        tint = if (active) EchoColors.Foreground else EchoColors.InactiveTab,
                        modifier = Modifier.padding(0.dp),
                    )
                }
            }
        }
        if (statusMessage != null) {
            BoxStatusStrip(statusMessage)
        }
    }
}

@Composable
private fun BoxStatusStrip(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(EchoColors.OfflineStripBg)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.labelSmall,
            color = EchoColors.OfflineStripFg,
        )
    }
}

enum class EchoTab(val route: String, val label: String, val icon: ImageVector) {
    Liked("liked", "Liked Songs", Icons.Default.Favorite),
    Albums("albums", "Albums", Icons.Default.Album),
    Search("search", "Search", Icons.Default.Search),
    Settings("settings", "Settings", Icons.Default.MoreHoriz),
}

// Kept for parity with echo's fuller tab set; podcasts/playlists can be added later.
val DefaultEchoTabs = listOf(
    EchoTab.Liked,
    EchoTab.Albums,
    EchoTab.Search,
    EchoTab.Settings,
)
