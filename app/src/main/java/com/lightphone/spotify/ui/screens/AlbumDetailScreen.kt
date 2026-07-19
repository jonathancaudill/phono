package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.lightphone.spotify.data.SpotifyTrack
import com.lightphone.spotify.data.toMetadata
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.CustomScrollView
import com.lightphone.spotify.ui.components.PhonoSwipeToActionRow
import com.lightphone.spotify.ui.components.PhonoTrackListItem
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.lightphone.spotify.ui.phono.PhonoTextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove

@Composable
fun AlbumDetailScreen(
    vm: AppViewModel,
    albumId: String,
    fallbackTitle: String,
    onBack: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onPlayTrack: (Int) -> Unit,
) {
    val state by vm.albumDetail.collectAsState()
    val downloads by vm.downloads.collectAsState()

    LaunchedEffect(albumId) { vm.loadAlbumDetail(albumId) }

    val album = state.album?.takeIf { it.id == albumId }
    val title = album?.name ?: fallbackTitle
    val tracks = album?.tracks?.items.orEmpty()
    val showSaveLoading = state.loading && !state.isSavedConfirmed
    val trackUris = remember(tracks) { tracks.map { it.uri } }
    val downloadLabel = remember(trackUris, downloads) { vm.collectionDownloadLabel(trackUris) }

    // Keep the last loaded track list visible until this screen leaves composition
    // so the layout (and scrollbar) do not collapse a frame before pop completes.
    var stableTracks by remember(albumId) { mutableStateOf<List<SpotifyTrack>>(emptyList()) }
    if (tracks.isNotEmpty()) {
        stableTracks = tracks
    }
    val displayTracks = if (tracks.isNotEmpty()) tracks else stableTracks
    val hasTrackContent = displayTracks.isNotEmpty()

    PhonoScreenShell(
        title = title,
        hideBackButton = false,
        onBack = onBack,
        rightIcon = if (state.isSaved) Icons.Default.Remove else Icons.Default.Add,
        onRightIconClick = { vm.toggleAlbumSave(albumId) },
        rightLoading = state.saving || showSaveLoading,
        onTitleClick = {
            album?.artists?.firstOrNull()?.id?.takeIf { it.isNotBlank() }?.let(onOpenArtist)
        },
        horizontalPadding = legacyNToGridDp(20),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (vm.downloadsSupported && hasTrackContent) {
            PhonoTextButton(
                text = downloadLabel,
                onClick = {
                    if (downloadLabel == "Remove download") vm.removeCurrentAlbumDownloads()
                    else vm.downloadCurrentAlbum()
                },
                modifier = Modifier.padding(bottom = legacyNToGridDp(12)),
            )
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = legacyNToGridDp(20)),
        ) {
            when {
                !hasTrackContent && state.loading -> EmptyListMessage("Loading…")
                !hasTrackContent && state.error != null -> EmptyListMessage(state.error!!)
                !hasTrackContent -> EmptyListMessage("No tracks found in this album.")
                else -> CustomScrollView {
                    itemsIndexed(displayTracks, key = { index, track -> track.id.ifBlank { "$index" } }) { index, track ->
                        val previous = displayTracks.getOrNull(index - 1)
                        Column {
                            if (previous != null && track.discNumber != previous.discNumber) {
                                Spacer(Modifier.height(legacyNToGridDp(40)))
                            }
                            PhonoSwipeToActionRow(
                                onSwipeAction = { vm.addTrackToQueue(track.toMetadata()) },
                            ) {
                                PhonoTrackListItem(
                                    trackNumber = track.trackNumber,
                                    name = track.name,
                                    artists = track.artists.joinToString { it.name },
                                    durationMs = track.durationMs,
                                    onClick = { onPlayTrack(index) },
                                    onLongClick = {
                                        vm.showTrackContextMenu(
                                            track.uri,
                                            track.id.ifBlank { track.uri.removePrefix("spotify:track:") },
                                        )
                                    },
                                )
                            }
                            Spacer(Modifier.height(legacyNToGridDp(8)))
                        }
                    }
                }
            }
        }
    }
}
