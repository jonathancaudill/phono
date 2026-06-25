package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.LibraryInfiniteList
import com.lightphone.spotify.ui.components.MonoContentContainer
import com.lightphone.spotify.ui.components.MonoMediaListItem
import com.lightphone.spotify.ui.components.buildLibraryAlphaIndex
import com.lightphone.spotify.ui.theme.n

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    vm: AppViewModel,
    onOpenPlaying: () -> Unit,
    onOpenPlaylist: (String, String) -> Unit,
    onCreatePlaylist: () -> Unit,
) {
    LaunchedEffect(Unit) {
        vm.ensurePlaylistsLoaded()
        vm.resumePlaylistsFillIfNeeded()
    }

    val state by vm.playlists.collectAsState()
    val listState = rememberLazyListState()
    val alphaIndex = remember(state.items) {
        buildLibraryAlphaIndex(state.items) { it.name }
    }

    MonoContentContainer(
        title = "Playlists",
        hideBackButton = true,
        leftIcon = Icons.Default.Add,
        onLeftIconClick = onCreatePlaylist,
        rightIcon = Icons.Default.GraphicEq,
        onRightIconClick = onOpenPlaying,
        horizontalPadding = n(20),
        modifier = Modifier.fillMaxSize(),
    ) {
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.refreshPlaylists() },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                state.error != null && state.items.isEmpty() ->
                    EmptyListMessage(state.error!!)
                state.initialLoading && state.items.isEmpty() ->
                    EmptyListMessage("Loading playlists…")
                state.isEmpty ->
                    EmptyListMessage("No playlists found.")
                else -> Column(Modifier.fillMaxSize()) {
                    if (state.error != null && state.items.isNotEmpty()) {
                        LibraryPartialSyncBanner(state.error!!)
                    }
                    LibraryInfiniteList(
                        listState = listState,
                        items = state.items,
                        remoteTotal = state.remoteTotal,
                        hasMore = state.hasMore,
                        appending = state.appending,
                        canLoadMore = state.canLoadMore,
                        itemKey = { it.playlist_id },
                        onEnsureBufferAhead = vm::ensurePlaylistsBufferAhead,
                        alphaIndex = alphaIndex,
                        onScrubToIndex = { index -> vm.scrollPlaylistsToIndex(listState, index) },
                        onScrubJumpChange = { active ->
                            if (active) vm.onScrubJumpStart() else vm.onScrubJumpEnd()
                        },
                    ) { _, playlist ->
                        MonoMediaListItem(
                            primaryText = playlist.name,
                            secondaryText = playlist.owner_name,
                            showImage = false,
                            placeholderIcon = Icons.Default.QueueMusic,
                            onClick = { onOpenPlaylist(playlist.playlist_id, playlist.name) },
                        )
                    }
                }
            }
        }
    }
}
