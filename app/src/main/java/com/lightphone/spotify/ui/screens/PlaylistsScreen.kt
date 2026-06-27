package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import com.lightphone.spotify.data.PlaylistFilter
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.LibraryInfiniteList
import com.lightphone.spotify.ui.components.PhonoContentContainer
import com.lightphone.spotify.ui.components.PhonoMediaListItem
import com.lightphone.spotify.ui.components.StyledText
import com.lightphone.spotify.ui.components.tap
import com.lightphone.spotify.ui.theme.PhonoColors
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
    val displayItems = state.displayItems
    val listState = rememberLazyListState()

    LaunchedEffect(state.filter) {
        listState.scrollToItem(0)
    }

    PhonoContentContainer(
        hideBackButton = true,
        leftIcon = Icons.Default.Add,
        onLeftIconClick = onCreatePlaylist,
        rightIcon = Icons.Default.GraphicEq,
        onRightIconClick = onOpenPlaying,
        horizontalPadding = n(20),
        modifier = Modifier.fillMaxSize(),
        titleContent = {
            PlaylistFilterChips(
                selected = state.filter,
                onSelect = vm::setPlaylistsFilter,
            )
        },
    ) {
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.refreshPlaylists() },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                state.error != null && displayItems.isEmpty() && state.items.isEmpty() ->
                    EmptyListMessage(state.error!!)
                state.initialLoading && displayItems.isEmpty() ->
                    EmptyListMessage("Loading playlists…")
                state.isEmpty ->
                    EmptyListMessage(
                        if (state.filter == PlaylistFilter.YourPlaylists) {
                            "No playlists created by you."
                        } else {
                            "No playlists found."
                        },
                    )
                else -> Column(Modifier.fillMaxSize()) {
                    if (state.error != null && state.items.isNotEmpty()) {
                        LibraryPartialSyncBanner(state.error!!)
                    }
                    LibraryInfiniteList(
                        listState = listState,
                        items = displayItems,
                        remoteTotal = state.displayRemoteTotal,
                        hasMore = state.hasMore,
                        appending = state.appending,
                        canLoadMore = state.canLoadMore,
                        itemKey = { it.playlist_id },
                        onEnsureBufferAhead = vm::ensurePlaylistsBufferAhead,
                    ) { _, playlist ->
                        PhonoMediaListItem(
                            primaryText = playlist.name,
                            secondaryText = playlist.owner_name,
                            showImage = false,
                            placeholderIcon = Icons.Default.QueueMusic,
                            onClick = { onOpenPlaylist(playlist.playlist_id, playlist.name) },
                            onLongClick = {
                                vm.showPlaylistContextMenu(
                                    playlistId = playlist.playlist_id,
                                    uri = playlist.uri.ifBlank { "spotify:playlist:${playlist.playlist_id}" },
                                    ownerId = playlist.owner_id,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistFilterChips(
    selected: PlaylistFilter,
    onSelect: (PlaylistFilter) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(n(8))) {
        PlaylistFilter.entries.forEach { filter ->
            PlaylistFilterChip(
                filter = filter,
                active = filter == selected,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
private fun PlaylistFilterChip(
    filter: PlaylistFilter,
    active: Boolean,
    onSelect: (PlaylistFilter) -> Unit,
) {
    Box(
        modifier = Modifier
            .background(if (active) PhonoColors.Foreground else PhonoColors.PlaceholderBg)
            .tap { onSelect(filter) }
            .padding(horizontal = n(14), vertical = n(8)),
    ) {
        StyledText(
            filter.label,
            size = 16,
            lineHeight = 18,
            color = if (active) PhonoColors.Background else PhonoColors.Foreground,
            maxLines = 1,
        )
    }
}
