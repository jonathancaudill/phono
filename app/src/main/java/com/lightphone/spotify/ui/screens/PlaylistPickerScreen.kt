package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.CustomScrollView
import com.lightphone.spotify.ui.components.MonoContentContainer
import com.lightphone.spotify.ui.components.MonoMediaListItem
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

    LaunchedEffect(trackUri) {
        vm.loadPlaylistPicker(trackUri)
    }

    MonoContentContainer(
        title = "Add to playlist",
        hideBackButton = false,
        onBack = onBack,
        rightIconVisible = false,
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
                            MonoMediaListItem(
                                primaryText = playlist.name,
                                secondaryText = playlist.owner_name,
                                showImage = false,
                                placeholderIcon = Icons.Default.QueueMusic,
                                disabled = state.adding,
                                onClick = {
                                    vm.addTrackToPlaylistFromPicker(playlist.playlist_id, onAdded)
                                },
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
