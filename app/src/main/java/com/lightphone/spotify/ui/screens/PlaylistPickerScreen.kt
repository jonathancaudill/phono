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
import com.lightphone.spotify.ui.components.PhonoSquareCheckbox
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.lightphone.spotify.ui.phono.PhonoTextButton
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

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

    PhonoScreenShell(
        title = "Add to playlist",
        hideBackButton = false,
        onBack = onBack,
        rightIcon = Icons.Default.Check,
        onRightIconClick = { vm.addTrackToSelectedPlaylists(onAdded) },
        rightIconVisible = hasSelection,
        rightLoading = state.adding,
        horizontalPadding = legacyNToGridDp(20),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (state.statusMessage != null) {
            LightText(
                text = state.statusMessage!!,
                variant = LightTextVariant.Detail,
                color = PhonoSemanticColors.Placeholder,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.error != null) {
            LightText(text = state.error!!, variant = LightTextVariant.Detail, color = PhonoSemanticColors.Error)
        }
        Column(Modifier.weight(1f)) {
            when {
                state.loading && state.playlists.isEmpty() -> EmptyListMessage("Loading playlists…")
                state.playlists.isEmpty() -> EmptyListMessage("No editable playlists.")
                else -> CustomScrollView(modifier = Modifier.weight(1f)) {
                    state.playlists.forEach { playlist ->
                        item(key = playlist.playlist_id) {
                            val alreadyContains = playlist.playlist_id in state.containingPlaylistIds
                            val selected = alreadyContains ||
                                playlist.playlist_id in state.selectedPlaylistIds
                            PlaylistPickerRow(
                                name = playlist.name,
                                ownerName = playlist.owner_name,
                                selected = selected,
                                disabled = state.adding || alreadyContains,
                                onToggle = { vm.togglePlaylistPickerSelection(playlist.playlist_id) },
                            )
                        }
                    }
                }
            }
            PhonoTextButton(
                text = "New playlist",
                onClick = onCreatePlaylist,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = legacyNToGridDp(12)),
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
    val colors = LightThemeTokens.colors
    val textColor = if (disabled) PhonoSemanticColors.DisabledIcon else colors.content
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = legacyNToGridDp(50))
            .lightClickable(enabled = !disabled, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(legacyNToGridDp(36))
                .padding(end = legacyNToGridDp(8)),
            contentAlignment = Alignment.Center,
        ) {
            PhonoSquareCheckbox(checked = selected, enabled = !disabled)
        }
        Column(
            Modifier
                .weight(1f)
                .padding(end = legacyNToGridDp(10)),
        ) {
            LightText(
                text = name,
                variant = LightTextVariant.Copy,
                color = textColor,
                maxLines = 1,
            )
            LightText(
                text = ownerName,
                variant = LightTextVariant.Detail,
                color = textColor,
                maxLines = 1,
            )
        }
    }
}
