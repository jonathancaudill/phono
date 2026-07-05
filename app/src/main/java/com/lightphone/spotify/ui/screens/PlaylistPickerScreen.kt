package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.CustomScrollView
import com.lightphone.spotify.ui.components.PhonoSquareCheckbox
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
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
    val libraryPlaylists by vm.playlists.collectAsState()

    LaunchedEffect(trackUri) {
        vm.loadPlaylistPicker(trackUri)
    }

    val pickerReady = state.trackUri == trackUri
    val displayPlaylists = remember(state.playlists, libraryPlaylists.items, libraryPlaylists.currentUserId, pickerReady) {
        when {
            pickerReady && state.playlists.isNotEmpty() -> state.playlists
            else -> vm.cachedEditablePlaylists()
        }
    }
    val showListLoading = displayPlaylists.isEmpty() && (!pickerReady || state.loading)
    val containingIds = if (pickerReady) state.containingPlaylistIds else emptySet()
    val likedSelected = if (pickerReady) state.likedSongsSelected else false

    PhonoScreenShell(
        title = "Add to playlist",
        hideBackButton = false,
        onBack = onBack,
        rightIcon = Icons.Default.Check,
        onRightIconClick = { vm.applyPlaylistPickerChanges(onAdded) },
        rightIconVisible = displayPlaylists.isNotEmpty() || !state.loading,
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
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                showListLoading -> EmptyListMessage("Loading playlists…")
                else -> CustomScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = legacyNToGridDp(12)),
                ) {
                    item(key = "liked_songs") {
                        PlaylistPickerRow(
                            name = "Liked Songs",
                            ownerName = "Your library",
                            selected = likedSelected,
                            disabled = state.adding,
                            onToggle = { vm.togglePlaylistPickerLikedSongs() },
                        )
                    }
                    displayPlaylists.forEach { playlist ->
                        item(key = playlist.playlist_id) {
                            val alreadyContains = playlist.playlist_id in containingIds
                            val selected = alreadyContains ||
                                playlist.playlist_id in state.selectedPlaylistIds
                            PlaylistPickerRow(
                                name = playlist.name,
                                ownerName = playlist.owner_name.ifBlank { playlist.owner_id },
                                selected = selected,
                                disabled = state.adding || alreadyContains,
                                onToggle = { vm.togglePlaylistPickerSelection(playlist.playlist_id) },
                            )
                        }
                    }
                }
            }
            LightText(
                text = "NEW PLAYLIST",
                variant = LightTextVariant.Button,
                align = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LightThemeTokens.colors.background)
                    .padding(vertical = legacyNToGridDp(12))
                    .lightClickable(onClick = onCreatePlaylist),
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
