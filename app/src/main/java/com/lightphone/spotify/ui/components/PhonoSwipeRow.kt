package com.lightphone.spotify.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntOffset
import com.lightphone.spotify.ui.theme.PhonoColors
import com.lightphone.spotify.ui.theme.n
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Swipe a row right to reveal a queue action on the left (keeps the screen's
 * right edge free for the library scrollbar).
 */
@Composable
fun PhonoSwipeToActionRow(
    onSwipeAction: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val actionWidth = n(48)
    val actionWidthPx = with(LocalDensity.current) { actionWidth.toPx() }
    val threshold = actionWidthPx * 0.6f
    val touchSlop = LocalViewConfiguration.current.touchSlop
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val animatedOffset by animateFloatAsState(
        targetValue = dragOffset,
        animationSpec = tween(durationMillis = if (isDragging) 0 else 120),
        label = "swipeRowOffset",
    )

    val revealProgress = (animatedOffset / actionWidthPx).coerceIn(0f, 1f)
    val iconColor = lerp(PhonoColors.Placeholder, PhonoColors.Foreground, revealProgress)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(actionWidthPx, touchSlop) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var totalDx = 0f
                    var totalDy = 0f
                    var horizontalLocked = false
                    var verticalLocked = false

                    drag(down.id) { change ->
                        val delta = change.positionChange()
                        if (!horizontalLocked && !verticalLocked) {
                            totalDx += delta.x
                            totalDy += delta.y
                            if (abs(totalDx) > touchSlop || abs(totalDy) > touchSlop) {
                                when {
                                    abs(totalDx) > abs(totalDy) * 1.5f -> {
                                        horizontalLocked = true
                                        isDragging = true
                                    }
                                    abs(totalDy) > abs(totalDx) * 1.5f -> {
                                        verticalLocked = true
                                    }
                                }
                            }
                        }
                        if (horizontalLocked) {
                            change.consume()
                            dragOffset = (dragOffset + delta.x).coerceIn(0f, actionWidthPx)
                        }
                    }

                    if (horizontalLocked && dragOffset >= threshold) {
                        onSwipeAction()
                    }
                    dragOffset = 0f
                    isDragging = false
                }
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
                .background(PhonoColors.Background),
        ) {
            content()
        }
    }
}
