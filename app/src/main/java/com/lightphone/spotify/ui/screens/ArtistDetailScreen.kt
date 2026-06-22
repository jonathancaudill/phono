package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
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
import com.lightphone.spotify.ui.components.EchoMediaListItem
import com.lightphone.spotify.ui.components.EchoTrackListItem
import com.lightphone.spotify.ui.theme.EchoColors

@Composable
fun ArtistDetailScreen(
    vm: AppViewModel,
    artistId: String,
    onBack: () -> Unit,
    onOpenAlbum: (String, String) -> Unit,
    onPlayTopTrack: (Int) -> Unit,
) {
    val state by vm.artistDetail.collectAsState()

    LaunchedEffect(artistId) { vm.loadArtistDetail(artistId) }

    val artist = state.artist

    EchoContentContainer(
        title = artist?.name ?: "Artist",
        hideBackButton = false,
        onBack = onBack,
        rightIconVisible = false,
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            state.loading -> Box(Modifier.fillMaxSize())
            state.error != null && artist == null -> {
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
                                imageUrl = artist?.images?.firstOrNull()?.url,
                                modifier = Modifier.size(200.dp),
                                placeholderIcon = Icons.Default.Person,
                            )
                        }
                        val followers = artist?.followers?.total
                        if (followers != null && followers > 0) {
                            Text(
                                "${followers / 1000}K followers",
                                style = MaterialTheme.typography.bodyMedium,
                                color = EchoColors.Placeholder,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )
                        }
                    }
                    if (state.topTracks.isNotEmpty()) {
                        item {
                            Text(
                                "Popular",
                                style = MaterialTheme.typography.titleLarge,
                                color = EchoColors.Foreground,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        itemsIndexed(state.topTracks, key = { _, t -> t.id }) { index, track ->
                            EchoTrackListItem(
                                trackNumber = index + 1,
                                name = track.name,
                                artists = track.artists.joinToString { it.name },
                                durationMs = track.durationMs,
                                onClick = { onPlayTopTrack(index) },
                            )
                        }
                    }
                    if (state.albums.isNotEmpty()) {
                        item {
                            Text(
                                "Albums",
                                style = MaterialTheme.typography.titleLarge,
                                color = EchoColors.Foreground,
                                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                            )
                        }
                        itemsIndexed(state.albums, key = { _, a -> a.id }) { _, album ->
                            EchoMediaListItem(
                                primaryText = album.name,
                                secondaryText = album.artists.joinToString { it.name },
                                imageUrl = album.images.firstOrNull()?.url,
                                placeholderIcon = Icons.Default.Album,
                                onClick = { onOpenAlbum(album.id, album.name) },
                            )
                        }
                    }
                }
            }
        }
    }
}
