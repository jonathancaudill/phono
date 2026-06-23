package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.CustomScrollView
import com.lightphone.spotify.ui.components.MonoContentContainer
import com.lightphone.spotify.ui.components.MonoMediaListItem
import com.lightphone.spotify.ui.components.StyledText
import com.lightphone.spotify.ui.theme.MonoColors
import com.lightphone.spotify.ui.theme.n

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LikedSongsScreen(
    vm: AppViewModel,
    onOpenPlaying: () -> Unit,
    onPlayTrack: (Int) -> Unit,
) {
    val state by vm.likedSongs.collectAsState()

    LaunchedEffect(Unit) {
        vm.loadLikedSongs(refresh = false)
    }

    MonoContentContainer(
        title = "Liked Songs",
        hideBackButton = true,
        rightIcon = Icons.Default.GraphicEq,
        onRightIconClick = onOpenPlaying,
        horizontalPadding = n(20),
        modifier = Modifier.fillMaxSize(),
    ) {
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.loadLikedSongs(refresh = true) },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                state.error != null && state.items.isEmpty() ->
                    EmptyListMessage(state.error!!)
                !state.loading && state.items.isEmpty() ->
                    EmptyListMessage("No saved tracks found.")
                else -> CustomScrollView(verticalArrangement = Arrangement.spacedBy(n(8))) {
                    itemsIndexed(state.items, key = { index, t -> "${t.uri}-$index" }) { index, track ->
                        MonoMediaListItem(
                            primaryText = track.title,
                            secondaryText = track.artists,
                            imageUrl = track.artUrl,
                            placeholderIcon = Icons.Default.MusicNote,
                            onClick = { onPlayTrack(index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun EmptyListMessage(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        StyledText(
            message,
            size = 16,
            color = MonoColors.Foreground,
            textAlign = TextAlign.Center,
        )
    }
}
