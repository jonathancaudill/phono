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
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.lightphone.spotify.ui.theme.MonoColors
import com.lightphone.spotify.ui.theme.n

@Composable
fun MonoNavbar(
    tabs: List<MonoTab>,
    currentTab: MonoTab,
    onTabSelected: (MonoTab) -> Unit,
    statusMessage: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MonoColors.Background)
                .padding(horizontal = n(20), vertical = n(11)),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val active = tab == currentTab
                Icon(
                    tab.icon,
                    contentDescription = tab.label,
                    tint = if (active) MonoColors.Foreground else MonoColors.InactiveTab,
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
            .background(MonoColors.OfflineStripBg),
        contentAlignment = Alignment.Center,
    ) {
        StyledText(message, size = 12, color = MonoColors.OfflineStripFg)
    }
}

enum class MonoTab(val route: String, val label: String, val icon: ImageVector) {
    Liked("liked", "Liked Songs", Icons.Default.Favorite),
    Albums("albums", "Albums", Icons.Default.Album),
    Search("search", "Search", Icons.Default.Search),
    Settings("settings", "Settings", Icons.Default.MoreHoriz),
}

val DefaultMonoTabs = listOf(
    MonoTab.Liked,
    MonoTab.Albums,
    MonoTab.Search,
    MonoTab.Settings,
)
