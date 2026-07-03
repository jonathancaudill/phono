package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.defaultMinSize
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
import com.lightphone.spotify.ui.components.PhonoFallbackImage
import com.lightphone.spotify.ui.components.formatTime
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoHeaderIcon
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

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

    PhonoScreenShell(
        title = " ",
        hideBackButton = false,
        onBack = onBack,
        rightIcon = Icons.AutoMirrored.Filled.QueueMusic,
        onRightIconClick = onOpenQueue,
        rightIconVisible = hasTrack,
        horizontalPadding = legacyNToGridDp(20),
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
                        .padding(bottom = legacyNToGridDp(20))
                        .size(legacyNToGridDp(200)),
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .padding(bottom = legacyNToGridDp(20)),
                ) {
                    LightText(
                        text = if (hasTrack) playback.title.orEmpty() else "No song playing",
                        variant = LightTextVariant.Copy,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        align = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (playback.albumId != null) {
                                    Modifier.lightClickable { playback.albumId?.let(onOpenAlbum) }
                                } else {
                                    Modifier
                                }
                            ),
                    )
                    LightText(
                        text = if (hasTrack) playback.artist.orEmpty() else "Go back and play something!",
                        variant = LightTextVariant.Detail,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        align = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                ProgressBar(playback, onSeek = { vm.seek(it) })
                PlaybackControls(playback, vm)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .height(legacyNToGridDp(50))
                    .padding(bottom = legacyNToGridDp(20)),
                contentAlignment = Alignment.Center,
            ) {
                if (hasTrack) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onAddToPlaylist != null && playback.currentUri != null) {
                            PhonoHeaderIcon(
                                icon = Icons.Default.PlaylistAdd,
                                onClick = { onAddToPlaylist(playback.currentUri!!) },
                                modifier = Modifier.size(legacyNToGridDp(30)),
                                contentDescription = "Add to playlist",
                            )
                        }
                        PhonoHeaderIcon(
                            icon = if (extras.isTrackSaved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            onClick = { if (!extras.savePending) vm.toggleCurrentTrackSave() },
                            modifier = Modifier.size(legacyNToGridDp(30)),
                            contentDescription = "Like",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressBar(playback: PlaybackUiState, onSeek: (Long) -> Unit) {
    val colors = LightThemeTokens.colors
    var lastDurationMs by remember(playback.currentUri) { mutableLongStateOf(0L) }
    if (playback.durationMs > 0L) {
        lastDurationMs = playback.durationMs
    }
    val duration = (if (playback.durationMs > 0L) playback.durationMs else lastDurationMs).coerceAtLeast(1)

    var scrubPositionMs by remember(playback.currentUri) { mutableLongStateOf(-1L) }
    val displayPositionMs = if (scrubPositionMs >= 0L) scrubPositionMs else playback.positionMs
    val displayProgress = (displayPositionMs.toFloat() / duration).coerceIn(0f, 1f)

    Column(
        Modifier
            .fillMaxWidth(0.9f)
            .defaultMinSize(minHeight = legacyNToGridDp(40))
            .pointerInput(duration) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    fun seekAt(x: Float) {
                        val fraction = (x / size.width).coerceIn(0f, 1f)
                        scrubPositionMs = (duration * fraction).toLong()
                    }
                    seekAt(down.position.x)
                    drag(down.id) { change ->
                        change.consume()
                        seekAt(change.position.x)
                    }
                    onSeek(scrubPositionMs.coerceAtLeast(0L))
                    scrubPositionMs = -1L
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(legacyNToGridDp(6)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(legacyNToGridDp(2))
                    .align(Alignment.Center)
                    .background(colors.content),
            )
            Box(
                Modifier
                    .fillMaxWidth(displayProgress)
                    .fillMaxHeight()
                    .align(Alignment.CenterStart)
                    .background(colors.content),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = legacyNToGridDp(3), bottom = legacyNToGridDp(6)),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LightText(text = formatTime(displayPositionMs), variant = LightTextVariant.Micro)
            LightText(text = formatTime(duration), variant = LightTextVariant.Micro)
        }
    }
}

@Composable
private fun PlaybackControls(playback: PlaybackUiState, vm: AppViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .padding(bottom = legacyNToGridDp(20)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaybackModeIcon(
            icon = Icons.Default.Shuffle,
            active = playback.shuffleEnabled,
            contentDescription = "Shuffle",
            onClick = vm::toggleShuffle,
        )
        PhonoHeaderIcon(
            icon = Icons.Default.SkipPrevious,
            onClick = vm::previous,
            modifier = Modifier.size(legacyNToGridDp(52)),
            contentDescription = "Previous",
        )
        PhonoHeaderIcon(
            icon = if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            onClick = { if (playback.isPlaying) vm.pause() else vm.resume() },
            modifier = Modifier.size(legacyNToGridDp(52)),
            contentDescription = "Play/Pause",
        )
        PhonoHeaderIcon(
            icon = Icons.Default.SkipNext,
            onClick = vm::next,
            modifier = Modifier.size(legacyNToGridDp(52)),
            contentDescription = "Next",
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
    val colors = LightThemeTokens.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PhonoHeaderIcon(
            icon = icon,
            onClick = onClick,
            modifier = Modifier.size(legacyNToGridDp(30)),
            contentDescription = contentDescription,
        )
        Spacer(Modifier.height(legacyNToGridDp(4)))
        if (active) {
            Box(
                Modifier
                    .size(legacyNToGridDp(5))
                    .background(colors.content, CircleShape),
            )
        } else {
            Spacer(Modifier.height(legacyNToGridDp(5)))
        }
    }
}
