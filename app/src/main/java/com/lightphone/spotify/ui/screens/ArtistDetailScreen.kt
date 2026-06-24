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
import androidx.compose.material.icons.filled.Album
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.CustomScrollView
import com.lightphone.spotify.ui.components.MonoContentContainer
import com.lightphone.spotify.ui.components.MonoMediaListItem
import com.lightphone.spotify.ui.components.MonoTrackListItem
import com.lightphone.spotify.ui.components.StyledText
import com.lightphone.spotify.ui.theme.MonoColors
import com.lightphone.spotify.ui.theme.n

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

    MonoContentContainer(
        title = artist?.name ?: "Artist",
        hideBackButton = false,
        onBack = onBack,
        rightIconVisible = false,
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
                state.error != null && artist == null -> EmptyListMessage(state.error!!)
                else -> CustomScrollView {
                    item("meta") {
                        val followers = artist?.followers?.total
                        if (followers != null && followers > 0) {
                            StyledText(
                                "${followers / 1000}K followers",
                                size = 16,
                                color = MonoColors.Placeholder,
                                modifier = Modifier.padding(bottom = n(16)),
                            )
                        }
                    }
                    if (state.topTracks.isNotEmpty()) {
                        item("popular-header") {
                            StyledText(
                                "Popular",
                                size = 20,
                                color = MonoColors.Foreground,
                                modifier = Modifier.padding(bottom = n(8)),
                            )
                        }
                        itemsIndexed(state.topTracks, key = { _, t -> t.id }) { index, track ->
                            Column {
                                MonoTrackListItem(
                                    trackNumber = index + 1,
                                    name = track.name,
                                    artists = track.artists.joinToString { it.name },
                                    durationMs = track.durationMs,
                                    onClick = { onPlayTopTrack(index) },
                                )
                                Spacer(Modifier.height(n(8)))
                            }
                        }
                    }
                    if (state.albums.isNotEmpty()) {
                        item("albums-header") {
                            StyledText(
                                "Albums",
                                size = 20,
                                color = MonoColors.Foreground,
                                modifier = Modifier.padding(top = n(16), bottom = n(8)),
                            )
                        }
                        itemsIndexed(state.albums, key = { _, a -> a.id }) { _, album ->
                            Column {
                                MonoMediaListItem(
                                    primaryText = album.name,
                                    secondaryText = album.artists.joinToString { it.name },
                                    showImage = false,
                                    placeholderIcon = Icons.Default.Album,
                                    onClick = { onOpenAlbum(album.id, album.name) },
                                )
                                Spacer(Modifier.height(n(8)))
                            }
                        }
                    }
                }
            }
        }
    }
}
