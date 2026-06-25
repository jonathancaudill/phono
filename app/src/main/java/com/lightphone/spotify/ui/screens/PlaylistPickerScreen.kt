package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.CustomScrollView
import com.lightphone.spotify.ui.components.MonoContentContainer
import com.lightphone.spotify.ui.components.MonoSquareCheckbox
import com.lightphone.spotify.ui.components.MonoStyledButton
import com.lightphone.spotify.ui.components.StyledText
import com.lightphone.spotify.ui.components.tap
import com.lightphone.spotify.ui.theme.MonoColors
import com.lightphone.spotify.ui.theme.n

@Composable
fun PlaylistPickerScreen(
    vm: AppViewModel,
    trackUri: String,
    onBack: () -> Unit,
    onCreatePlaylist: () -> Unit,
    onAdded: () -> Unit,
) {
    val state by vm.playlistPicker.collectAsState()
    val hasSelection = state.selectedPlaylistIds.isNotEmpty()

    LaunchedEffect(trackUri) {
        vm.loadPlaylistPicker(trackUri)
    }

    MonoContentContainer(
        title = "Add to playlist",
        hideBackButton = false,
        onBack = onBack,
        rightIcon = Icons.Default.Check,
        onRightIconClick = { vm.addTrackToSelectedPlaylists(onAdded) },
        rightIconVisible = hasSelection,
        rightLoading = state.adding,
        horizontalPadding = n(20),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (state.statusMessage != null) {
            StyledText(
                state.statusMessage!!,
                size = 14,
                color = MonoColors.Placeholder,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.error != null) {
            StyledText(state.error!!, size = 14, color = MonoColors.Error)
        }
        Column(Modifier.weight(1f)) {
            when {
                state.loading && state.playlists.isEmpty() -> EmptyListMessage("Loading playlists…")
                state.playlists.isEmpty() -> EmptyListMessage("No editable playlists.")
                else -> CustomScrollView(modifier = Modifier.weight(1f)) {
                    state.playlists.forEach { playlist ->
                        item(key = playlist.playlist_id) {
                            val selected = playlist.playlist_id in state.selectedPlaylistIds
                            PlaylistPickerRow(
                                name = playlist.name,
                                ownerName = playlist.owner_name,
                                selected = selected,
                                disabled = state.adding,
                                onToggle = { vm.togglePlaylistPickerSelection(playlist.playlist_id) },
                            )
                        }
                    }
                }
            }
            MonoStyledButton(
                text = "New playlist",
                onClick = onCreatePlaylist,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = n(12)),
            )
        }
    }
}

@Composable
private fun PlaylistPickerRow(
    name: String,
    ownerName: String,
    selected: Boolean,
    disabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = n(50))
            .tap(enabled = !disabled, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(n(36))
                .padding(end = n(8)),
            contentAlignment = Alignment.Center,
        ) {
            MonoSquareCheckbox(checked = selected, enabled = !disabled)
        }
        Column(
            Modifier
                .weight(1f)
                .padding(end = n(10)),
        ) {
            StyledText(
                name,
                size = 22,
                lineHeight = 24,
                color = if (disabled) MonoColors.DisabledIcon else MonoColors.Foreground,
                maxLines = 1,
            )
            StyledText(
                ownerName,
                size = 16,
                lineHeight = 18,
                color = if (disabled) MonoColors.DisabledIcon else MonoColors.Foreground,
                maxLines = 1,
            )
        }
    }
}
