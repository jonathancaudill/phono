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
import com.lightphone.spotify.ui.components.MonoContentContainer
import com.lightphone.spotify.ui.components.StyledText
import com.lightphone.spotify.ui.components.tap
import com.lightphone.spotify.ui.theme.MonoColors
import com.lightphone.spotify.ui.theme.n

private val QueueArrowEndPadding = n(14)

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

    MonoContentContainer(
        title = "Queue",
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
                                    Spacer(Modifier.height(n(8)))
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
                                    Spacer(Modifier.height(n(8)))
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
            .padding(top = n(16), bottom = n(8)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StyledText(
            title,
            size = 20,
            color = MonoColors.Foreground,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            StyledText(
                actionLabel,
                size = 16,
                color = MonoColors.Placeholder,
                modifier = Modifier.tap(onClick = onAction),
            )
        }
    }
}

@Composable
private fun QueueTrackRow(item: QueueUiItem) {
    Column(Modifier.fillMaxWidth()) {
        StyledText(
            item.title,
            size = 22,
            lineHeight = 24,
            color = MonoColors.Foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        StyledText(
            item.artists,
            size = 16,
            lineHeight = 18,
            color = MonoColors.Placeholder,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(end = n(8)),
        ) {
            StyledText(
                item.title,
                size = 22,
                lineHeight = 24,
                color = MonoColors.Foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StyledText(
                item.artists,
                size = 16,
                lineHeight = 18,
                color = MonoColors.Placeholder,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.padding(end = QueueArrowEndPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = "Move up",
                tint = if (index > 0) MonoColors.Foreground else MonoColors.Placeholder,
                modifier = Modifier
                    .size(n(32))
                    .tap(enabled = index > 0, onClick = onMoveUp),
            )
            Spacer(Modifier.width(n(4)))
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Move down",
                tint = if (index < total - 1) MonoColors.Foreground else MonoColors.Placeholder,
                modifier = Modifier
                    .size(n(32))
                    .tap(enabled = index < total - 1, onClick = onMoveDown),
            )
        }
    }
}
