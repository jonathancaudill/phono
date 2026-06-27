package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.lightphone.spotify.ffi.RepeatMode
import com.lightphone.spotify.playback.PlaybackUiState
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.PhonoContentContainer
import com.lightphone.spotify.ui.components.PhonoFallbackImage
import com.lightphone.spotify.ui.components.StyledText
import com.lightphone.spotify.ui.components.formatTime
import com.lightphone.spotify.ui.components.tap
import com.lightphone.spotify.ui.theme.PhonoColors
import com.lightphone.spotify.ui.theme.n

@Composable
fun PlayingScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenQueue: () -> Unit,
    onAddToPlaylist: ((String) -> Unit)? = null,
) {
    val playback by vm.playback.collectAsState()
    val extras by vm.playingExtras.collectAsState()

    LaunchedEffect(playback.currentUri) {
        vm.refreshPlayingScreen()
    }

    val hasTrack = playback.currentUri != null || playback.title != null

    PhonoContentContainer(
        title = " ",
        hideBackButton = false,
        onBack = onBack,
        rightIcon = Icons.AutoMirrored.Filled.QueueMusic,
        onRightIconClick = onOpenQueue,
        rightIconVisible = hasTrack,
        horizontalPadding = n(20),
        topPadding = n(0),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PhonoFallbackImage(
                    imageUrl = playback.artUrl,
                    placeholderIcon = Icons.Default.MusicNote,
                    crossfade = false,
                    modifier = Modifier
                        .padding(bottom = n(20))
                        .size(n(200)),
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .padding(bottom = n(20)),
                ) {
                    StyledText(
                        if (hasTrack) playback.title.orEmpty() else "No song playing",
                        size = 22,
                        lineHeight = 24,
                        color = PhonoColors.Foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (playback.albumId != null) {
                                    Modifier.tap { playback.albumId?.let(onOpenAlbum) }
                                } else {
                                    Modifier
                                }
                            ),
                    )
                    StyledText(
                        if (hasTrack) playback.artist.orEmpty() else "Go back and play something!",
                        size = 14,
                        lineHeight = 16,
                        color = PhonoColors.Foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                ProgressBar(playback, onSeek = { vm.seek(it) })
                PlaybackControls(playback, vm)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .height(n(50))
                    .padding(bottom = n(20)),
                contentAlignment = Alignment.Center,
            ) {
                if (hasTrack) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onAddToPlaylist != null && playback.currentUri != null) {
                            Icon(
                                Icons.Default.PlaylistAdd,
                                contentDescription = "Add to playlist",
                                tint = PhonoColors.Foreground,
                                modifier = Modifier
                                    .size(n(30))
                                    .tap { onAddToPlaylist(playback.currentUri!!) },
                            )
                        }
                        Icon(
                            if (extras.isTrackSaved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Like",
                            tint = PhonoColors.Foreground,
                            modifier = Modifier
                                .size(n(30))
                                .tap(enabled = !extras.savePending) { vm.toggleCurrentTrackSave() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressBar(playback: PlaybackUiState, onSeek: (Long) -> Unit) {
    var lastDurationMs by remember(playback.currentUri) { mutableLongStateOf(0L) }
    if (playback.durationMs > 0L) {
        lastDurationMs = playback.durationMs
    }
    val duration = (if (playback.durationMs > 0L) playback.durationMs else lastDurationMs).coerceAtLeast(1)
    val progress = (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f)

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth(0.9f)
                .height(n(6))
                .pointerInput(duration) {
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        onSeek((duration * fraction).toLong())
                    }
                },
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(n(2))
                    .align(Alignment.Center)
                    .background(PhonoColors.Foreground),
            )
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .align(Alignment.CenterStart)
                    .background(PhonoColors.Foreground),
            )
        }
        Row(
            Modifier
                .fillMaxWidth(0.9f)
                .padding(top = n(3), bottom = n(6)),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StyledText(formatTime(playback.positionMs), size = 12, color = PhonoColors.Foreground)
            StyledText(formatTime(duration), size = 12, color = PhonoColors.Foreground)
        }
    }
}

@Composable
private fun PlaybackControls(playback: PlaybackUiState, vm: AppViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .padding(bottom = n(20)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaybackModeIcon(
            icon = Icons.Default.Shuffle,
            active = playback.shuffleEnabled,
            contentDescription = "Shuffle",
            onClick = vm::toggleShuffle,
        )
        Icon(
            Icons.Default.SkipPrevious,
            contentDescription = "Previous",
            tint = PhonoColors.Foreground,
            modifier = Modifier.size(n(52)).tap { vm.previous() },
        )
        Icon(
            if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = "Play/Pause",
            tint = PhonoColors.Foreground,
            modifier = Modifier.size(n(52)).tap { if (playback.isPlaying) vm.pause() else vm.resume() },
        )
        Icon(
            Icons.Default.SkipNext,
            contentDescription = "Next",
            tint = PhonoColors.Foreground,
            modifier = Modifier.size(n(52)).tap { vm.next() },
        )
        PlaybackModeIcon(
            icon = when (playback.repeatMode) {
                RepeatMode.TRACK -> Icons.Default.RepeatOne
                else -> Icons.Default.Repeat
            },
            active = playback.repeatMode != RepeatMode.OFF,
            contentDescription = "Repeat",
            onClick = vm::toggleRepeat,
        )
    }
}

@Composable
private fun PlaybackModeIcon(
    icon: ImageVector,
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.tap(onClick = onClick),
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = PhonoColors.Foreground,
            modifier = Modifier.size(n(30)),
        )
        Spacer(Modifier.height(n(4)))
        if (active) {
            Box(
                Modifier
                    .size(n(5))
                    .background(PhonoColors.Foreground, CircleShape),
            )
        } else {
            Spacer(Modifier.height(n(5)))
        }
    }
}
