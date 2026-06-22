package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lightphone.spotify.data.TrackMetadata
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.EchoContentContainer
import com.lightphone.spotify.ui.components.EchoMediaListItem
import com.lightphone.spotify.ui.theme.EchoColors

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

    EchoContentContainer(
        title = "Liked Songs",
        rightIcon = Icons.Default.GraphicEq,
        onRightIconClick = onOpenPlaying,
        modifier = Modifier.fillMaxSize(),
    ) {
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.loadLikedSongs(refresh = true) },
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.loading && state.items.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = EchoColors.Foreground)
                    }
                }
                state.error != null && state.items.isEmpty() -> {
                    Text(state.error!!, color = EchoColors.Error)
                }
                state.items.isEmpty() -> {
                    Text("No saved tracks found.", style = MaterialTheme.typography.bodyLarge, color = EchoColors.Placeholder)
                }
                else -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(state.items) { index, track ->
                            LikedTrackRow(track) { onPlayTrack(index) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LikedTrackRow(track: TrackMetadata, onClick: () -> Unit) {
    EchoMediaListItem(
        primaryText = track.title,
        secondaryText = track.artists,
        imageUrl = track.artUrl,
        onClick = onClick,
    )
}
