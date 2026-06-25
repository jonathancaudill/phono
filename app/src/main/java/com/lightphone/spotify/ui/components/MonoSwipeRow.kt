package com.lightphone.spotify.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.lightphone.spotify.ui.theme.MonoColors
import com.lightphone.spotify.ui.theme.n
import kotlin.math.roundToInt

/**
 * Swipe a row right to reveal a queue action on the left (keeps the screen's
 * right edge free for the library scrollbar).
 */
@Composable
fun MonoSwipeToActionRow(
    onSwipeAction: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val actionWidth = n(48)
    val actionWidthPx = with(LocalDensity.current) { actionWidth.toPx() }
    val threshold = actionWidthPx * 0.28f
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val animatedOffset by animateFloatAsState(
        targetValue = dragOffset,
        animationSpec = tween(durationMillis = if (isDragging) 0 else 120),
        label = "swipeRowOffset",
    )

    val revealProgress = (animatedOffset / actionWidthPx).coerceIn(0f, 1f)
    val iconColor = lerp(MonoColors.Placeholder, MonoColors.Foreground, revealProgress)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(actionWidthPx) {
                detectHorizontalDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = {
                        if (dragOffset >= threshold) {
                            onSwipeAction()
                        }
                        dragOffset = 0f
                        isDragging = false
                    },
                    onHorizontalDrag = { _, amount ->
                        dragOffset = (dragOffset + amount).coerceIn(0f, actionWidthPx)
                    },
                )
            },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(actionWidth)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = "Add to queue",
                tint = iconColor,
                modifier = Modifier.size(n(24)),
            )
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                .fillMaxWidth()
                .background(MonoColors.Background),
        ) {
            content()
        }
    }
}
