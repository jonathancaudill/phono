package com.lightphone.spotify.ui.phono

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.Dp
import kotlin.math.abs

/** Full-screen scrim that absorbs touches so they do not reach content beneath. */
fun Modifier.consumeScrimTouches(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown().consume()
    }
}

/**
 * Left-edge horizontal swipe to go back. Touches that begin outside [edgeWidth]
 * are ignored so list scrolls, row swipe-to-queue, and the right-edge scrollbar
 * keep working.
 */
fun Modifier.leftEdgeSwipeBack(
    enabled: Boolean,
    edgeWidth: Dp,
    distanceThreshold: Dp,
    onBack: () -> Unit,
): Modifier {
    if (!enabled) return this
    return pointerInput(edgeWidth, distanceThreshold, onBack) {
        val edgeWidthPx = edgeWidth.toPx()
        val thresholdPx = distanceThreshold.toPx()
        val touchSlop = viewConfiguration.touchSlop

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.position.x > edgeWidthPx) return@awaitEachGesture

            var totalDx = 0f
            var totalDy = 0f
            var dragDistance = 0f
            var horizontalLocked = false
            var verticalLocked = false

            drag(down.id) { change ->
                val delta = change.positionChange()
                if (!horizontalLocked && !verticalLocked) {
                    totalDx += delta.x
                    totalDy += delta.y
                    if (abs(totalDx) > touchSlop || abs(totalDy) > touchSlop) {
                        when {
                            abs(totalDx) > abs(totalDy) * 1.5f && totalDx > 0f -> {
                                horizontalLocked = true
                                dragDistance = totalDx
                            }
                            abs(totalDy) > abs(totalDx) * 1.5f -> {
                                verticalLocked = true
                            }
                        }
                    }
                } else if (horizontalLocked) {
                    change.consume()
                    dragDistance += delta.x
                }
            }

            if (horizontalLocked && dragDistance >= thresholdPx) {
                onBack()
            }
        }
    }
}
