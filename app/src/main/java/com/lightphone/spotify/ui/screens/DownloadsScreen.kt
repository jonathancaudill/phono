package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.lightphone.spotify.data.TrackMetadata
import com.lightphone.spotify.data.local.DownloadedCollectionWithProgress
import com.lightphone.spotify.data.local.DownloadedTrackEntity
import com.lightphone.spotify.playback.download.DownloadStates
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.CustomScrollView
import com.lightphone.spotify.ui.components.PhonoMediaListItem
import com.lightphone.spotify.ui.components.PhonoSwipeToActionRow
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens

@Composable
fun DownloadsScreen(
    vm: AppViewModel,
    onOpenPlaying: () -> Unit,
    onOpenCollection: (collectionUri: String, title: String) -> Unit,
) {
    val collections by vm.downloadCollections.collectAsState()
    val listState = rememberLazyListState()
    var editMode by remember { mutableStateOf(false) }
    val colors = LightThemeTokens.colors

    PhonoScreenShell(
        title = "Downloads",
        hideBackButton = true,
        leftIcon = if (editMode) Icons.Default.Check else Icons.Default.Edit,
        onLeftIconClick = { editMode = !editMode },
        rightLightIcon = LightIcons.AUDIO_MESSAGE,
        onRightIconClick = onOpenPlaying,
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
                collections.isEmpty() -> EmptyListMessage(
                    "No downloads yet.\nDownload an album or playlist from its detail screen.",
                )
                else -> CustomScrollView(state = listState) {
                    items(collections, key = { it.uri }) { row ->
                        Column {
                            PhonoMediaListItem(
                                primaryText = row.name,
                                secondaryText = collectionSubtitle(row),
                                showImage = false,
                                onEditDelete = if (editMode) {
                                    { vm.removeDownloadCollection(row.uri) }
                                } else {
                                    null
                                },
                                onClick = {
                                    if (!editMode) onOpenCollection(row.uri, row.name)
                                },
                            )
                            Spacer(Modifier.height(legacyNToGridDp(8)))
                        }
                    }
                    if (editMode) {
                        item {
                            LightText(
                                text = "Tap Cancel to remove a download.",
                                variant = LightTextVariant.Micro,
                                color = colors.content.copy(alpha = 0.55f),
                                modifier = Modifier.padding(top = legacyNToGridDp(12)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadCollectionDetailScreen(
    vm: AppViewModel,
    collectionUri: String,
    title: String,
    onBack: () -> Unit,
    onPlayTrack: (TrackMetadata) -> Unit,
) {
    val tracksFlow = remember(collectionUri) { vm.observeDownloadCollectionTracks(collectionUri) }
    val tracks by tracksFlow.collectAsState()
    val listState = rememberLazyListState()
    var editMode by remember { mutableStateOf(false) }
    val colors = LightThemeTokens.colors

    PhonoScreenShell(
        title = title,
        hideBackButton = false,
        onBack = {
            if (editMode) editMode = false else onBack()
        },
        rightIcon = if (editMode) Icons.Default.Check else Icons.Default.Edit,
        onRightIconClick = { editMode = !editMode },
        rightIconVisible = tracks.isNotEmpty(),
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
                tracks.isEmpty() -> EmptyListMessage("No tracks in this download.")
                else -> CustomScrollView(state = listState) {
                    items(tracks, key = { it.uri }) { row ->
                        val track = remember(row.uri) { row.toTrackMetadata() }
                        val completed = row.state == DownloadStates.COMPLETED
                        Column {
                            if (!editMode && completed) {
                                PhonoSwipeToActionRow(
                                    onSwipeAction = { vm.addTrackToQueue(track) },
                                ) {
                                    PhonoMediaListItem(
                                        primaryText = row.title,
                                        secondaryText = downloadTrackSubtitle(row),
                                        showImage = false,
                                        onClick = { onPlayTrack(track) },
                                    )
                                }
                            } else {
                                PhonoMediaListItem(
                                    primaryText = row.title,
                                    secondaryText = downloadTrackSubtitle(row),
                                    showImage = false,
                                    onEditDelete = if (editMode) {
                                        { vm.removeDownload(track) }
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        if (!editMode && completed) onPlayTrack(track)
                                    },
                                )
                            }
                            Spacer(Modifier.height(legacyNToGridDp(8)))
                        }
                    }
                    item {
                        LightText(
                            text = if (editMode) {
                                "Tap Cancel to remove a track."
                            } else {
                                "Tap a finished track to play. Swipe right to queue."
                            },
                            variant = LightTextVariant.Micro,
                            color = colors.content.copy(alpha = 0.55f),
                            modifier = Modifier.padding(top = legacyNToGridDp(12)),
                        )
                    }
                }
            }
        }
    }
}

private fun collectionSubtitle(row: DownloadedCollectionWithProgress): String {
    val kind = if (row.type == "playlist") "Playlist" else "Album"
    val total = row.track_count
    val done = row.completed_count
    return when {
        total == 0 -> kind
        row.failed_count > 0 && done + row.in_progress_count == 0 ->
            "$kind · Failed"
        row.in_progress_count > 0 || (done in 1 until total) ->
            "$kind · $done/$total · Downloading…"
        done == total ->
            "$kind · $total songs"
        else ->
            "$kind · $done/$total"
    }
}

private fun downloadTrackSubtitle(row: DownloadedTrackEntity): String {
    val artists = row.artists.ifBlank { "Unknown artist" }
    val status = when (row.state) {
        DownloadStates.COMPLETED -> null
        DownloadStates.DOWNLOADING, DownloadStates.QUEUED, DownloadStates.RESTARTING -> "Downloading…"
        DownloadStates.FAILED -> "Failed"
        DownloadStates.STOPPED -> "Paused"
        DownloadStates.REMOVING -> "Removing…"
        else -> "Pending"
    }
    return if (status != null) "$artists · $status" else artists
}

private fun DownloadedTrackEntity.toTrackMetadata() = TrackMetadata(
    uri = uri,
    title = title,
    artists = artists,
    album = album,
    durationMs = duration_ms,
    artUrl = art_url,
)
