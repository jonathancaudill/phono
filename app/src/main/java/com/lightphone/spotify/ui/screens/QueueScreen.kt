package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.lightphone.spotify.playback.QueueUiItem
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.CustomScrollView
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

@Composable
fun QueueScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
) {
    val playback by vm.playback.collectAsState()
    val queue = playback.queue

    LaunchedEffect(Unit) {
        vm.refreshQueue()
    }

    val hasContent = queue.nowPlaying != null ||
        queue.nextInQueue.isNotEmpty() ||
        queue.nextFromContext.isNotEmpty()

    PhonoScreenShell(
        title = "Queue",
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
                !hasContent -> EmptyListMessage("Nothing playing.")
                else -> CustomScrollView {
                    queue.nowPlaying?.let { item ->
                        item("now-playing-header") {
                            QueueSectionHeader("Now playing")
                        }
                        item("now-playing-${item.uri}") {
                            QueueTrackRow(item = item)
                        }
                    }

                    if (queue.nextInQueue.isNotEmpty()) {
                        item("next-in-queue-header") {
                            QueueSectionHeader(
                                title = "Next in queue",
                                actionLabel = "Clear queue",
                                onAction = { vm.clearManualQueue() },
                            )
                        }
                        queue.nextInQueue.forEachIndexed { index, item ->
                            item("manual-${item.uri}-$index") {
                                Column {
                                    ReorderableQueueRow(
                                        item = item,
                                        index = index,
                                        total = queue.nextInQueue.size,
                                        onMoveUp = { vm.moveQueueItemUp(index) },
                                        onMoveDown = { vm.moveQueueItemDown(index) },
                                    )
                                    Spacer(Modifier.height(legacyNToGridDp(8)))
                                }
                            }
                        }
                    }

                    if (queue.nextFromContext.isNotEmpty()) {
                        item("next-from-header") {
                            val label = queue.contextLabel?.let { "Next from: $it" } ?: "Next up"
                            QueueSectionHeader(label)
                        }
                        queue.nextFromContext.forEachIndexed { index, item ->
                            item("context-${item.uri}-$index") {
                                Column {
                                    ReorderableQueueRow(
                                        item = item,
                                        index = index,
                                        total = queue.nextFromContext.size,
                                        onMoveUp = { vm.moveContextItemUp(index) },
                                        onMoveDown = { vm.moveContextItemDown(index) },
                                    )
                                    Spacer(Modifier.height(legacyNToGridDp(8)))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueSectionHeader(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = legacyNToGridDp(16), bottom = legacyNToGridDp(8)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = title,
            variant = LightTextVariant.Fine,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            LightText(
                text = actionLabel,
                variant = LightTextVariant.Detail,
                color = PhonoSemanticColors.Placeholder,
                modifier = Modifier.lightClickable(onClick = onAction),
            )
        }
    }
}

@Composable
private fun QueueTrackRow(item: QueueUiItem) {
    Column(Modifier.fillMaxWidth()) {
        LightText(
            text = item.title,
            variant = LightTextVariant.Copy,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LightText(
            text = item.artists,
            variant = LightTextVariant.Detail,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = legacyNToGridDp(6)),
        )
    }
}

@Composable
private fun ReorderableQueueRow(
    item: QueueUiItem,
    index: Int,
    total: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val colors = LightThemeTokens.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(end = legacyNToGridDp(4)),
        ) {
            LightText(
                text = item.title,
                variant = LightTextVariant.Copy,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LightText(
                text = item.artists,
                variant = LightTextVariant.Detail,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = legacyNToGridDp(6)),
            )
        }
        Row(
            modifier = Modifier.padding(end = legacyNToGridDp(14)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = "Move up",
                tint = if (index > 0) colors.content else PhonoSemanticColors.Placeholder,
                modifier = Modifier
                    .size(legacyNToGridDp(44))
                    .lightClickable(enabled = index > 0, onClick = onMoveUp),
            )
            Spacer(Modifier.width(legacyNToGridDp(24)))
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Move down",
                tint = if (index < total - 1) colors.content else PhonoSemanticColors.Placeholder,
                modifier = Modifier
                    .size(legacyNToGridDp(44))
                    .lightClickable(enabled = index < total - 1, onClick = onMoveDown),
            )
        }
    }
}
