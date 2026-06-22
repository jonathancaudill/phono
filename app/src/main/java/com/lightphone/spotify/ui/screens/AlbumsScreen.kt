package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.lightphone.spotify.data.SpotifySavedAlbum
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.EchoContentContainer
import com.lightphone.spotify.ui.components.EchoMediaListItem
import com.lightphone.spotify.ui.theme.EchoColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    vm: AppViewModel,
    onOpenPlaying: () -> Unit,
    onOpenAlbum: (String, String) -> Unit,
) {
    val state by vm.albums.collectAsState()

    LaunchedEffect(Unit) {
        if (state.items.isEmpty() && !state.loading) {
            vm.loadAlbums(refresh = false)
        }
    }

    EchoContentContainer(
        title = "Albums",
        rightIcon = Icons.Default.GraphicEq,
        onRightIconClick = onOpenPlaying,
        modifier = Modifier.fillMaxSize(),
    ) {
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.loadAlbums(refresh = true) },
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
                    Text("No saved albums found.", style = MaterialTheme.typography.bodyLarge, color = EchoColors.Placeholder)
                }
                else -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.items, key = { it.album.id }) { saved ->
                            SavedAlbumRow(saved) {
                                onOpenAlbum(saved.album.id, saved.album.name)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedAlbumRow(saved: SpotifySavedAlbum, onClick: () -> Unit) {
    EchoMediaListItem(
        primaryText = saved.album.name,
        secondaryText = saved.album.artists.joinToString { it.name },
        imageUrl = saved.album.images.firstOrNull()?.url,
        placeholderIcon = Icons.Default.Album,
        onClick = onClick,
    )
}
