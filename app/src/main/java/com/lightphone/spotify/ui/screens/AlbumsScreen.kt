package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.CustomScrollView
import com.lightphone.spotify.ui.components.MonoContentContainer
import com.lightphone.spotify.ui.components.MonoMediaListItem
import com.lightphone.spotify.ui.theme.n

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

    MonoContentContainer(
        title = "Albums",
        hideBackButton = true,
        rightIcon = Icons.Default.GraphicEq,
        onRightIconClick = onOpenPlaying,
        horizontalPadding = n(20),
        modifier = Modifier.fillMaxSize(),
    ) {
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.loadAlbums(refresh = true) },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                state.error != null && state.items.isEmpty() ->
                    EmptyListMessage(state.error!!)
                !state.loading && state.items.isEmpty() ->
                    EmptyListMessage("No saved albums found.")
                else -> CustomScrollView(verticalArrangement = Arrangement.spacedBy(n(8))) {
                    items(state.items, key = { it.album.id }) { saved ->
                        MonoMediaListItem(
                            primaryText = saved.album.name,
                            secondaryText = saved.album.artists.joinToString { it.name },
                            imageUrl = saved.album.images.firstOrNull()?.url,
                            placeholderIcon = Icons.Default.Album,
                            onClick = { onOpenAlbum(saved.album.id, saved.album.name) },
                        )
                    }
                }
            }
        }
    }
}
