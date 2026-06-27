package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.lightphone.spotify.ui.components.buildLibraryDateIndex
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.LibraryInfiniteList
import com.lightphone.spotify.ui.components.MonoContentContainer
import com.lightphone.spotify.ui.components.MonoMediaListItem
import com.lightphone.spotify.ui.components.ScrollbarMode
import com.lightphone.spotify.ui.theme.n

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    vm: AppViewModel,
    onOpenPlaying: () -> Unit,
    onOpenAlbum: (String, String) -> Unit,
) {
    LaunchedEffect(Unit) {
        vm.ensureSavedAlbumsLoaded()
        vm.resumeSavedAlbumsFillIfNeeded()
    }

    val state by vm.savedAlbums.collectAsState()
    val listState = rememberLazyListState()
    val dateIndex = remember(state.items) {
        buildLibraryDateIndex(state.items) { it.added_at }
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
            onRefresh = { vm.refreshSavedAlbums() },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                state.error != null && state.items.isEmpty() ->
                    EmptyListMessage(state.error!!)
                state.initialLoading && state.items.isEmpty() ->
                    EmptyListMessage("Loading albums…")
                state.isEmpty ->
                    EmptyListMessage("No saved albums found.")
                else -> Column(Modifier.fillMaxSize()) {
                    if (state.error != null && state.items.isNotEmpty()) {
                        // TODO: wire styled banner in separate UI task
                        LibraryPartialSyncBanner(state.error!!)
                    }
                    LibraryInfiniteList(
                        listState = listState,
                        items = state.items,
                        remoteTotal = state.remoteTotal,
                        hasMore = state.hasMore,
                        appending = state.appending,
                        canLoadMore = state.canLoadMore,
                        itemKey = { it.album_id },
                        onEnsureBufferAhead = vm::ensureSavedAlbumsBufferAhead,
                        dateIndex = dateIndex,
                        scrollbarMode = ScrollbarMode.ScrubHoldOnly,
                        onScrubToIndex = { index -> vm.scrollSavedAlbumsToIndex(listState, index) },
                        onScrubJumpChange = { active ->
                            if (active) vm.onScrubJumpStart() else vm.onScrubJumpEnd()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) { _, saved ->
                        MonoMediaListItem(
                            primaryText = saved.name,
                            secondaryText = saved.artist_names,
                            showImage = false,
                            placeholderIcon = Icons.Default.Album,
                            onClick = { onOpenAlbum(saved.album_id, saved.name) },
                            onLongClick = {
                                vm.showAlbumContextMenu(
                                    saved.album_id,
                                    saved.uri.ifBlank { "spotify:album:${saved.album_id}" },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
