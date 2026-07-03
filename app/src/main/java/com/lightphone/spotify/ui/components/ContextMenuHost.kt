package com.lightphone.spotify.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import com.lightphone.spotify.ui.AppViewModel

@Composable
fun ContextMenuHost(
    vm: AppViewModel,
    onNavigateToPlaylistPicker: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.contextMenu.collectAsState()
    val playlists by vm.playlists.collectAsState()

    LaunchedEffect(state.navigateToPlaylistPickerUri) {
        val uri = state.navigateToPlaylistPickerUri ?: return@LaunchedEffect
        onNavigateToPlaylistPicker(uri)
        vm.consumeNavigateToPlaylistPicker()
    }

    val overlayModifier = modifier
        .fillMaxSize()
        .zIndex(10f)

    if (state.showCopied) {
        PhonoCopiedOverlay(onDismiss = vm::dismissCopiedOverlay, modifier = overlayModifier)
        return
    }

    state.deleteConfirm?.let { confirm ->
        PhonoDeleteConfirmOverlay(
            message = "Deleting this playlist will permanently remove it from your library, are you sure?",
            onConfirm = { vm.confirmDeletePlaylist() },
            onCancel = vm::cancelDeletePlaylist,
            modifier = overlayModifier,
        )
        return
    }

    state.target?.let { target ->
        val items = vm.contextMenuItemsFor(target, playlists.currentUserId)
        PhonoContextMenuOverlay(
            items = items,
            onItemClick = { item -> vm.onContextMenuAction(item.action) },
            onDismiss = vm::dismissContextMenu,
            modifier = overlayModifier,
        )
    }
}
