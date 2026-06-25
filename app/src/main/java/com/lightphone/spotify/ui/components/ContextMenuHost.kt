package com.lightphone.spotify.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.lightphone.spotify.ui.AppViewModel

@Composable
fun ContextMenuHost(
    vm: AppViewModel,
    onNavigateToPlaylistPicker: (String) -> Unit,
) {
    val state by vm.contextMenu.collectAsState()
    val playlists by vm.playlists.collectAsState()

    LaunchedEffect(state.navigateToPlaylistPickerUri) {
        val uri = state.navigateToPlaylistPickerUri ?: return@LaunchedEffect
        onNavigateToPlaylistPicker(uri)
        vm.consumeNavigateToPlaylistPicker()
    }

    if (state.showCopied) {
        MonoCopiedOverlay(onDismiss = vm::dismissCopiedOverlay)
        return
    }

    state.deleteConfirm?.let { confirm ->
        MonoDeleteConfirmOverlay(
            message = "Deleting this playlist will permanently remove it from your library, are you sure?",
            onConfirm = { vm.confirmDeletePlaylist() },
            onCancel = vm::cancelDeletePlaylist,
        )
        return
    }

    state.target?.let { target ->
        val items = vm.contextMenuItemsFor(target, playlists.currentUserId)
        MonoContextMenuOverlay(
            items = items,
            onItemClick = { item -> vm.onContextMenuAction(item.action) },
            onDismiss = vm::dismissContextMenu,
        )
    }
}
