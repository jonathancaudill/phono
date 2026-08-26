package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.lightphone.spotify.data.SpotifyAlbumSimple
import com.lightphone.spotify.data.toMetadata
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.CustomScrollView
import com.lightphone.spotify.ui.components.PhonoMediaListItem
import com.lightphone.spotify.ui.components.PhonoSwipeToActionRow
import com.lightphone.spotify.ui.components.PhonoTrackListItem
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant

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

    val artist = state.artist?.takeIf { it.id == artistId }
    val openAlbumMenu: (SpotifyAlbumSimple) -> Unit = { album ->
        vm.showAlbumContextMenu(
            album.id,
            album.uri.ifBlank {
                com.lightphone.spotify.data.backend.collectionUri(
                    vm.backendChoice,
                    com.lightphone.spotify.data.backend.CollectionKind.Album,
                    album.id,
                )
            },
        )
    }

    PhonoScreenShell(
        title = artist?.name ?: "Artist",
        hideBackButton = false,
        onBack = onBack,
        rightIconVisible = false,
        horizontalPadding = legacyNToGridDp(20),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = legacyNToGridDp(20)),
        ) {
            when {
                state.error != null && artist == null -> EmptyListMessage(state.error!!)
                state.loading && artist == null -> EmptyListMessage("Loading artist…")
                else -> CustomScrollView {
                    if (state.topTracks.isNotEmpty()) {
                        item("popular-header") {
                            LightText(
                                text = "Popular",
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.padding(bottom = legacyNToGridDp(8)),
                            )
                        }
                        itemsIndexed(state.topTracks, key = { _, t -> t.id }) { index, track ->
                            Column {
                                PhonoSwipeToActionRow(
                                    onSwipeAction = { vm.addTrackToQueue(track.toMetadata()) },
                                ) {
                                    PhonoTrackListItem(
                                        trackNumber = index + 1,
                                        name = track.name,
                                        artists = track.artists.joinToString { it.name },
                                        durationMs = track.durationMs,
                                        onClick = { onPlayTopTrack(index) },
                                        onLongClick = {
                                            vm.showTrackContextMenu(track.uri, track.id)
                                        },
                                    )
                                }
                                Spacer(Modifier.height(legacyNToGridDp(8)))
                            }
                        }
                    }
                    discographySection(
                        headerKey = "albums-header",
                        title = "Albums",
                        itemKeyPrefix = "album",
                        albums = state.albums,
                        padTop = state.topTracks.isNotEmpty(),
                        onOpenAlbum = onOpenAlbum,
                        onAlbumLongClick = openAlbumMenu,
                    )
                    discographySection(
                        headerKey = "singles-header",
                        title = "Singles",
                        itemKeyPrefix = "single",
                        albums = state.singles,
                        padTop = state.topTracks.isNotEmpty() || state.albums.isNotEmpty(),
                        onOpenAlbum = onOpenAlbum,
                        onAlbumLongClick = openAlbumMenu,
                    )
                }
            }
        }
    }
}

private fun LazyListScope.discographySection(
    headerKey: String,
    title: String,
    itemKeyPrefix: String,
    albums: List<SpotifyAlbumSimple>,
    padTop: Boolean,
    onOpenAlbum: (String, String) -> Unit,
    onAlbumLongClick: (SpotifyAlbumSimple) -> Unit,
) {
    if (albums.isEmpty()) return
    item(headerKey) {
        LightText(
            text = title,
            variant = LightTextVariant.Copy,
            modifier = Modifier.padding(
                top = if (padTop) legacyNToGridDp(16) else legacyNToGridDp(0),
                bottom = legacyNToGridDp(8),
            ),
        )
    }
    itemsIndexed(albums, key = { _, a -> "$itemKeyPrefix-${a.id}" }) { _, album ->
        Column {
            PhonoMediaListItem(
                primaryText = album.name,
                secondaryText = album.artists.joinToString { it.name },
                showImage = false,
                placeholderIcon = Icons.Default.Album,
                onClick = { onOpenAlbum(album.id, album.name) },
                onLongClick = { onAlbumLongClick(album) },
            )
            Spacer(Modifier.height(legacyNToGridDp(8)))
        }
    }
}
