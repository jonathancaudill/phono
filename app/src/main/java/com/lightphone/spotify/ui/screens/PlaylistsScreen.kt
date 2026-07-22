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
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.lightphone.spotify.data.PlaylistFilter
import com.lightphone.spotify.data.backend.BackendChoice
import com.lightphone.spotify.data.tidal.TidalPlaylistOwners
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.LibraryInfiniteList
import com.lightphone.spotify.ui.components.PhonoMediaListItem
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

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

    PhonoScreenShell(
        hideBackButton = true,
        leftIcon = Icons.Default.Add,
        onLeftIconClick = onCreatePlaylist,
        rightLightIcon = LightIcons.AUDIO_MESSAGE,
        onRightIconClick = onOpenPlaying,
        horizontalPadding = legacyNToGridDp(20),
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
                            secondaryText = playlistOwnerSecondary(
                                backendChoice = vm.backendChoice,
                                ownerId = playlist.owner_id,
                                ownerName = playlist.owner_name,
                                me = state.currentUserId,
                            ),
                            showImage = false,
                            placeholderIcon = Icons.AutoMirrored.Filled.PlaylistPlay,
                            onClick = { onOpenPlaylist(playlist.playlist_id, playlist.name) },
                            onLongClick = {
                                vm.showPlaylistContextMenu(
                                    playlistId = playlist.playlist_id,
                                    uri = playlist.uri.ifBlank {
                                        com.lightphone.spotify.data.backend.collectionUri(
                                            vm.backendChoice,
                                            com.lightphone.spotify.data.backend.CollectionKind.Playlist,
                                            playlist.playlist_id,
                                        )
                                    },
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

private fun playlistOwnerSecondary(
    backendChoice: BackendChoice,
    ownerId: String,
    ownerName: String,
    me: String?,
): String {
    if (backendChoice != BackendChoice.TIDAL) {
        return ownerName.ifBlank { ownerId }
    }
    return TidalPlaylistOwners.displayForUi(ownerId, ownerName, me)
}

@Composable
private fun PlaylistFilterChips(
    selected: PlaylistFilter,
    onSelect: (PlaylistFilter) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(legacyNToGridDp(8))) {
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
    val colors = LightThemeTokens.colors
    Box(
        modifier = Modifier
            .background(if (active) colors.content else PhonoSemanticColors.PlaceholderBg)
            .lightClickable { onSelect(filter) }
            .padding(horizontal = legacyNToGridDp(12), vertical = legacyNToGridDp(6)),
    ) {
        LightText(
            text = filter.label,
            variant = LightTextVariant.Copy,
            color = if (active) colors.background else colors.content,
            maxLines = 1,
        )
    }
}
