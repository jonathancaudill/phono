package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.EchoContentContainer
import com.lightphone.spotify.ui.components.EchoDetailCover
import com.lightphone.spotify.ui.components.EchoTrackListItem
import com.lightphone.spotify.ui.theme.EchoColors

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

    EchoContentContainer(
        title = title,
        hideBackButton = false,
        onBack = onBack,
        rightIcon = if (state.isSaved) Icons.Default.Remove else Icons.Default.Add,
        onRightIconClick = { vm.toggleAlbumSave(albumId) },
        rightLoading = state.saving,
        onTitleClick = {
            album?.artists?.firstOrNull()?.id?.takeIf { it.isNotBlank() }?.let(onOpenArtist)
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            state.loading -> Box(Modifier.fillMaxSize())
            state.error != null && album == null -> {
                Text(state.error!!, color = EchoColors.Error)
            }
            else -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            EchoDetailCover(
                                imageUrl = album?.images?.firstOrNull()?.url,
                                modifier = Modifier.size(200.dp),
                                placeholderIcon = Icons.Default.Album,
                            )
                        }
                    }
                    if (tracks.isEmpty()) {
                        item {
                            Text(
                                "No tracks found in this album.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = EchoColors.Placeholder,
                            )
                        }
                    } else {
                        itemsIndexed(tracks, key = { index, track -> track.id.ifBlank { "$index" } }) { index, track ->
                            val previous = tracks.getOrNull(index - 1)
                            if (previous != null && track.discNumber != previous.discNumber) {
                                Spacer(Modifier.height(40.dp))
                            }
                            EchoTrackListItem(
                                trackNumber = track.trackNumber,
                                name = track.name,
                                artists = track.artists.joinToString { it.name },
                                durationMs = track.durationMs,
                                onClick = { onPlayTrack(index) },
                            )
                        }
                    }
                }
            }
        }
    }
}
