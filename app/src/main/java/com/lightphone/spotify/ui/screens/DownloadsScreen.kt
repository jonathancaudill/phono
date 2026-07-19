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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.media3.exoplayer.offline.Download
import com.lightphone.spotify.data.TrackMetadata
import com.lightphone.spotify.data.local.DownloadedTrackEntity
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
    onPlayTrack: (TrackMetadata) -> Unit,
) {
    val downloads by vm.downloads.collectAsState()
    val listState = rememberLazyListState()
    val colors = LightThemeTokens.colors

    PhonoScreenShell(
        title = "Downloads",
        hideBackButton = true,
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
                downloads.isEmpty() -> EmptyListMessage(
                    "No downloads yet.\nDownload an album or playlist from its detail screen.",
                )
                else -> CustomScrollView(state = listState) {
                    items(downloads, key = { it.uri }) { row ->
                        val track = remember(row.uri) { row.toTrackMetadata() }
                        Column {
                            PhonoSwipeToActionRow(
                                onSwipeAction = { vm.removeDownload(track) },
                            ) {
                                PhonoMediaListItem(
                                    primaryText = row.title,
                                    secondaryText = downloadSubtitle(row),
                                    imageUrl = row.art_url,
                                    onClick = { onPlayTrack(track) },
                                    onLongClick = {
                                        vm.showTrackContextMenu(
                                            row.uri,
                                            row.uri.substringAfterLast(':'),
                                        )
                                    },
                                )
                            }
                            Spacer(Modifier.height(legacyNToGridDp(8)))
                        }
                    }
                    item {
                        LightText(
                            text = "Swipe a row to remove its download.",
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

private fun downloadSubtitle(row: DownloadedTrackEntity): String {
    val artists = row.artists.ifBlank { "Unknown artist" }
    val status = when (row.state) {
        Download.STATE_COMPLETED -> null
        Download.STATE_DOWNLOADING, Download.STATE_QUEUED, Download.STATE_RESTARTING -> "Downloading…"
        Download.STATE_FAILED -> "Failed"
        Download.STATE_STOPPED -> "Paused"
        Download.STATE_REMOVING -> "Removing…"
        else -> "Pending"
    }
    return if (status != null) "$artists · $status" else artists
}

private fun DownloadedTrackEntity.toTrackMetadata() = TrackMetadata(
    uri = uri,
    title = title,
    artists = artists,
    album = album,
    durationMs = 0L,
    artUrl = art_url,
)
