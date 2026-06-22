package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lightphone.spotify.playback.PlaybackUiState
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.EchoContentContainer
import com.lightphone.spotify.ui.components.EchoDetailCover
import com.lightphone.spotify.ui.components.formatTime
import com.lightphone.spotify.ui.theme.EchoColors

@Composable
fun PlayingScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
) {
    val playback by vm.playback.collectAsState()
    val extras by vm.playingExtras.collectAsState()

    LaunchedEffect(playback.currentUri) {
        vm.refreshPlayingSaveState()
    }

    val hasTrack = playback.currentUri != null || playback.title != null

    EchoContentContainer(
        title = " ",
        hideBackButton = false,
        onBack = onBack,
        rightIconVisible = false,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (hasTrack) {
                    EchoDetailCover(
                        imageUrl = playback.artUrl,
                        modifier = Modifier
                            .size(200.dp)
                            .padding(bottom = 20.dp),
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .padding(bottom = 20.dp),
                    ) {
                        Text(
                            playback.title ?: "",
                            style = MaterialTheme.typography.titleMedium,
                            color = EchoColors.Foreground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable {
                                // album navigation would need album id in playback state
                            },
                        )
                        Text(
                            playback.artist ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = EchoColors.Placeholder,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Box(
                        Modifier
                            .size(200.dp)
                            .background(EchoColors.PlaceholderBg)
                            .padding(bottom = 20.dp),
                    )
                    Text("No song playing", style = MaterialTheme.typography.titleMedium, color = EchoColors.Foreground)
                    Text("Go back and play something!", style = MaterialTheme.typography.bodyMedium, color = EchoColors.Placeholder)
                }

                if (hasTrack) {
                    ProgressBar(playback, onSeek = { vm.seek(it) })
                    PlaybackControls(playback, vm)
                }
            }

            if (hasTrack) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(onClick = { vm.toggleCurrentTrackSave() }, enabled = !extras.savePending) {
                        Icon(
                            if (extras.isTrackSaved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Like",
                            tint = EchoColors.Foreground,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    Box(Modifier.size(30.dp))
                    Box(Modifier.size(30.dp))
                    Box(Modifier.size(30.dp))
                }
            }
        }
    }
}

@Composable
private fun ProgressBar(playback: PlaybackUiState, onSeek: (Long) -> Unit) {
    val duration = playback.durationMs.coerceAtLeast(1)
    val progress = (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f)

    Column(Modifier.fillMaxWidth(0.9f)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clickable {
                    onSeek((duration * 0.5f).toLong())
                },
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.Center)
                    .background(EchoColors.Foreground.copy(alpha = 0.3f)),
            )
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .height(6.dp)
                    .align(Alignment.CenterStart)
                    .background(EchoColors.Foreground),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatTime(playback.positionMs), style = MaterialTheme.typography.labelSmall, color = EchoColors.Foreground)
            Text(formatTime(duration), style = MaterialTheme.typography.labelSmall, color = EchoColors.Foreground)
        }
    }
}

@Composable
private fun PlaybackControls(playback: PlaybackUiState, vm: AppViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .padding(bottom = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Shuffle, contentDescription = null, tint = EchoColors.Foreground.copy(alpha = 0.5f), modifier = Modifier.size(30.dp))
        IconButton(onClick = { vm.previous() }) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = EchoColors.Foreground, modifier = Modifier.size(52.dp))
        }
        IconButton(onClick = { if (playback.isPlaying) vm.pause() else vm.resume() }) {
            Icon(
                if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = "Play/Pause",
                tint = EchoColors.Foreground,
                modifier = Modifier.size(52.dp),
            )
        }
        IconButton(onClick = { vm.next() }) {
            Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = EchoColors.Foreground, modifier = Modifier.size(52.dp))
        }
        Icon(Icons.Default.Repeat, contentDescription = null, tint = EchoColors.Foreground.copy(alpha = 0.5f), modifier = Modifier.size(30.dp))
    }
}
