package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import com.lightphone.spotify.data.toMetadata
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.CustomScrollView
import com.lightphone.spotify.ui.components.MonoContentContainer
import com.lightphone.spotify.ui.components.MonoSwipeToActionRow
import com.lightphone.spotify.ui.components.MonoTrackEditActions
import com.lightphone.spotify.ui.components.MonoTrackListItem
import com.lightphone.spotify.ui.components.buildLibraryDateIndex
import com.lightphone.spotify.ui.theme.MonoColors
import com.lightphone.spotify.ui.theme.PublicSans
import com.lightphone.spotify.ui.theme.n
import com.lightphone.spotify.ui.theme.nSp

@Composable
fun PlaylistDetailScreen(
    vm: AppViewModel,
    playlistId: String,
    fallbackTitle: String,
    onBack: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    onAddToPlaylist: ((String) -> Unit)? = null,
) {
    val state by vm.playlistDetail.collectAsState()
    val listState = rememberLazyListState()
    var renameDraft by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(playlistId) { vm.loadPlaylistDetail(playlistId) }

    renameDraft?.let { draft ->
        RenamePlaylistOverlay(
            initialName = draft,
            onConfirm = { name ->
                vm.renamePlaylist(playlistId, name)
                renameDraft = null
            },
            onCancel = { renameDraft = null },
        )
        return
    }

    val title = state.detail?.name ?: fallbackTitle
    val tracks = state.tracks
    val dateIndex = remember(tracks) {
        buildLibraryDateIndex(tracks) { it.addedAt }
    }

    MonoContentContainer(
        title = title,
        hideBackButton = false,
        onBack = onBack,
        rightIcon = when {
            state.isEditable -> if (state.editMode) Icons.Default.Check else Icons.Default.Edit
            state.isInLibrary -> Icons.Default.Remove
            else -> Icons.Default.Add
        },
        onRightIconClick = {
            if (state.isEditable) {
                if (!state.mutating) vm.togglePlaylistEditMode()
            } else if (!state.saving) {
                vm.togglePlaylistLibrary(playlistId)
            }
        },
        rightIconVisible = state.isEditable || state.detail != null,
        rightLoading = state.mutating || state.saving,
        onTitleClick = if (state.editMode && state.isEditable) {
            { renameDraft = title }
        } else {
            null
        },
        horizontalPadding = n(20),
        contentGap = n(0),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (state.mutationError != null) {
            LibraryPartialSyncBanner(state.mutationError!!)
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = n(20)),
        ) {
            when {
                state.error != null && state.detail == null -> EmptyListMessage(state.error!!)
                state.loading && state.detail == null -> EmptyListMessage("Loading playlist…")
                tracks.isEmpty() -> EmptyListMessage("No tracks in this playlist.")
                else -> CustomScrollView(
                    state = listState,
                    loadedItemCount = tracks.size,
                    dateIndex = dateIndex,
                    onScrubToIndex = { index ->
                        if (index in tracks.indices) listState.scrollToItem(index)
                    },
                ) {
                    itemsIndexed(tracks, key = { index, row -> row.uri.ifBlank { "$index" } }) { index, row ->
                        val track = row.track
                        Column {
                            PlaylistTrackRow(
                                name = track.name,
                                artists = track.artists.joinToString { it.name },
                                durationMs = track.durationMs,
                                editMode = state.editMode && state.isEditable,
                                mutating = state.mutating,
                                canMoveUp = index > 0,
                                canMoveDown = index < tracks.size - 1,
                                onPlay = { onPlayTrack(index) },
                                onAddToPlaylist = onAddToPlaylist?.let { callback ->
                                    { callback(track.uri) }
                                },
                                onRemove = { vm.removePlaylistTrack(playlistId, index) },
                                onMoveUp = { vm.movePlaylistTrack(playlistId, index, index - 1) },
                                onMoveDown = { vm.movePlaylistTrack(playlistId, index, index + 1) },
                                onSwipeToQueue = { vm.addTrackToQueue(track.toMetadata()) },
                            )
                            Spacer(Modifier.height(n(8)))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistTrackRow(
    name: String,
    artists: String,
    durationMs: Long,
    editMode: Boolean,
    mutating: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPlay: () -> Unit,
    onAddToPlaylist: (() -> Unit)?,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onSwipeToQueue: () -> Unit,
) {
    val item = @Composable {
        MonoTrackListItem(
            name = name,
            artists = artists,
            durationMs = durationMs,
            onClick = onPlay,
            onLongClick = onAddToPlaylist,
            editActions = if (editMode) {
                MonoTrackEditActions(
                    mutating = mutating,
                    canMoveUp = canMoveUp,
                    canMoveDown = canMoveDown,
                    onRemove = onRemove,
                    onMoveUp = onMoveUp,
                    onMoveDown = onMoveDown,
                )
            } else {
                null
            },
        )
    }
    if (editMode) {
        item()
    } else {
        MonoSwipeToActionRow(onSwipeAction = onSwipeToQueue) {
            item()
        }
    }
}

@Composable
private fun RenamePlaylistOverlay(
    initialName: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }

    MonoContentContainer(
        title = "Rename playlist",
        hideBackButton = false,
        onBack = onCancel,
        rightIconVisible = false,
        horizontalPadding = n(37),
        modifier = Modifier.fillMaxSize(),
    ) {
        BasicTextField(
            value = name,
            onValueChange = { name = it },
            textStyle = TextStyle(
                color = MonoColors.Foreground,
                fontSize = nSp(22),
                fontFamily = PublicSans,
            ),
            cursorBrush = SolidColor(MonoColors.Foreground),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = n(12)),
            singleLine = true,
        )
        com.lightphone.spotify.ui.components.MonoStyledButton(
            text = "Save",
            onClick = { onConfirm(name.trim()) },
        )
    }
}
