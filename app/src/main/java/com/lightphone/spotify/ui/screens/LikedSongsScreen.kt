package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.data.local.toTrackMetadata
import com.lightphone.spotify.ui.components.LibraryInfiniteList
import com.lightphone.spotify.ui.components.MonoContentContainer
import com.lightphone.spotify.ui.components.MonoMediaListItem
import com.lightphone.spotify.ui.components.MonoSwipeToActionRow
import com.lightphone.spotify.ui.components.ScrollbarMode
import com.lightphone.spotify.ui.components.StyledText
import com.lightphone.spotify.ui.components.buildLibraryDateIndex
import com.lightphone.spotify.ui.theme.MonoColors
import com.lightphone.spotify.ui.theme.n
import com.lightphone.spotify.ui.trackIdFromUri

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LikedSongsScreen(
    vm: AppViewModel,
    onOpenPlaying: () -> Unit,
    onPlayTrack: (Int) -> Unit,
) {
    LaunchedEffect(Unit) {
        vm.ensureLikedTracksLoaded()
        vm.resumeLikedTracksFillIfNeeded()
    }

    val state by vm.likedTracks.collectAsState()
    val listState = rememberLazyListState()
    val dateIndex = remember(state.items) {
        buildLibraryDateIndex(state.items) { it.added_at }
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
            onRefresh = { vm.refreshLikedTracks() },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                state.error != null && state.items.isEmpty() ->
                    EmptyListMessage(state.error!!)
                state.initialLoading && state.items.isEmpty() ->
                    EmptyListMessage("Loading liked songs…")
                state.isEmpty ->
                    EmptyListMessage("No saved tracks found.")
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
                        itemKey = { it.uri },
                        onEnsureBufferAhead = vm::ensureLikedTracksBufferAhead,
                        dateIndex = dateIndex,
                        scrollbarMode = ScrollbarMode.ScrubHoldOnly,
                        onScrubToIndex = { index -> vm.scrollLikedTracksToIndex(listState, index) },
                        onScrubJumpChange = { active ->
                            if (active) vm.onScrubJumpStart() else vm.onScrubJumpEnd()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) { index, track ->
                        MonoSwipeToActionRow(
                            onSwipeAction = { vm.addTrackToQueue(track.toTrackMetadata()) },
                        ) {
                            MonoMediaListItem(
                                primaryText = track.title,
                                secondaryText = track.artists,
                                showImage = false,
                                placeholderIcon = Icons.Default.MusicNote,
                                onClick = { onPlayTrack(index) },
                                onLongClick = {
                                    vm.showTrackContextMenu(
                                        track.uri,
                                        trackIdFromUri(track.uri),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LibraryPartialSyncBanner(message: String) {
    StyledText(
        message,
        size = 14,
        color = MonoColors.Foreground,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = n(4)),
    )
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
