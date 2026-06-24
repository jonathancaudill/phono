package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.CustomScrollView
import com.lightphone.spotify.ui.components.MonoContentContainer
import com.lightphone.spotify.ui.components.MonoTrackListItem
import com.lightphone.spotify.ui.theme.n

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

    LaunchedEffect(albumId) { vm.loadAlbumDetail(albumId) }

    val album = state.album
    val title = album?.name ?: fallbackTitle
    val tracks = album?.tracks?.items.orEmpty()

    MonoContentContainer(
        title = title,
        hideBackButton = false,
        onBack = onBack,
        rightIcon = if (state.isSaved) Icons.Default.Remove else Icons.Default.Add,
        onRightIconClick = { vm.toggleAlbumSave(albumId) },
        rightLoading = state.saving,
        onTitleClick = {
            album?.artists?.firstOrNull()?.id?.takeIf { it.isNotBlank() }?.let(onOpenArtist)
        },
        horizontalPadding = n(20),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = n(20)),
        ) {
            when {
                state.error != null && album == null -> EmptyListMessage(state.error!!)
                tracks.isEmpty() -> EmptyListMessage("No tracks found in this album.")
                else -> CustomScrollView {
                    itemsIndexed(tracks, key = { index, track -> track.id.ifBlank { "$index" } }) { index, track ->
                        val previous = tracks.getOrNull(index - 1)
                        Column {
                            if (previous != null && track.discNumber != previous.discNumber) {
                                Spacer(Modifier.height(n(40)))
                            }
                            MonoTrackListItem(
                                trackNumber = track.trackNumber,
                                name = track.name,
                                artists = track.artists.joinToString { it.name },
                                durationMs = track.durationMs,
                                onClick = { onPlayTrack(index) },
                            )
                            Spacer(Modifier.height(n(8)))
                        }
                    }
                }
            }
        }
    }
}
