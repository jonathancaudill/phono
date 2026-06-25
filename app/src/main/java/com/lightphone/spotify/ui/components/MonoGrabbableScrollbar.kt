package com.lightphone.spotify.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.lightphone.spotify.ui.theme.MonoColors
import com.lightphone.spotify.ui.theme.n
import androidx.compose.foundation.lazy.LazyListState
import kotlin.math.max

/**
 * Scrollbar geometry for a [LazyListState], used by [CustomScrollView] and drag-to-scrub.
 *
 * [virtualItemCount] lets infinite library lists reflect the remote total in thumb size;
 * [loadedItemCount] caps how far drag scrub can scroll until more pages arrive.
 */
data class ScrollbarLayout(
    val thumbHeight: Float,
    val thumbY: Float,
    val contentHeight: Float,
    val viewport: Float,
    val avgItemSize: Float,
    val itemTotal: Int,
)

fun LazyListState.computeScrollbarLayout(
    loadedItemCount: Int? = null,
    virtualItemCount: Int? = null,
    minThumbPx: Float,
): ScrollbarLayout? {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return null

    val avgItemSize = visible.sumOf { it.size }.toFloat() / visible.size
    if (avgItemSize <= 0f) return null

    val layoutTotal = info.totalItemsCount
    val itemTotal = max(virtualItemCount ?: layoutTotal, layoutTotal)
    val contentHeight = avgItemSize * itemTotal
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    if (viewport <= 0f) return null

    val visibleSize = visible.sumOf { it.size }.toFloat()
    if (contentHeight <= viewport && visibleSize <= viewport) return null

    val first = visible.first()
    val scrollPx = avgItemSize * first.index - first.offset

    val thumbHeight = max((viewport * viewport) / contentHeight, minThumbPx)
    val maxScroll = contentHeight - viewport
    val thumbTravel = viewport - thumbHeight
    val thumbY = if (maxScroll > 0f) {
        ((scrollPx / maxScroll) * thumbTravel).coerceIn(0f, thumbTravel)
    } else {
        0f
    }

    return ScrollbarLayout(
        thumbHeight = thumbHeight,
        thumbY = thumbY,
        contentHeight = contentHeight,
        viewport = viewport,
        avgItemSize = avgItemSize,
        itemTotal = itemTotal,
    )
}

/**
 * Maps a Y position on the scrollbar strip to a [LazyListState] scroll position.
 * [stripY] is relative to the top of the touch strip (not the thumb center offset).
 */
suspend fun LazyListState.scrollToStripY(
    stripY: Float,
    stripHeight: Float,
    layout: ScrollbarLayout,
    loadedItemCount: Int? = null,
) {
    val thumbTravel = stripHeight - layout.thumbHeight
    if (thumbTravel <= 0f) return
    val thumbY = (stripY - layout.thumbHeight / 2f).coerceIn(0f, thumbTravel)
    val maxScroll = layout.contentHeight - layout.viewport
    if (maxScroll <= 0f) return
    val scrollPx = (thumbY / thumbTravel) * maxScroll
    val maxIndex = loadedItemCount?.minus(1)?.coerceAtLeast(0)
        ?: max(layout.itemTotal - 1, 0)
    val index = (scrollPx / layout.avgItemSize).toInt().coerceIn(0, maxIndex)
    val offset = (scrollPx - index * layout.avgItemSize).toInt().coerceAtLeast(0)
    scroll { scrollToItem(index, offset) }
}

/** Visual track + thumb for the Mono list scrollbar (1px track, 5px thumb). */
@Composable
fun MonoGrabbableScrollbar(
    layout: ScrollbarLayout,
    modifier: Modifier = Modifier,
    trackWidth: Dp = n(1),
    thumbWidth: Dp = n(5),
    thumbRightOverhang: Dp = n(2),
    color: androidx.compose.ui.graphics.Color = MonoColors.Foreground,
) {
    val density = LocalDensity.current
    val trackWidthPx = with(density) { trackWidth.toPx() }
    val thumbWidthPx = with(density) { thumbWidth.toPx() }
    val thumbRightOverhangPx = with(density) { thumbRightOverhang.toPx() }

    Canvas(modifier.fillMaxSize()) {
        val trackLeft = size.width - trackWidthPx
        val thumbLeft = size.width - thumbWidthPx + thumbRightOverhangPx
        drawRect(
            color = color,
            topLeft = Offset(trackLeft, 0f),
            size = Size(trackWidthPx, size.height),
        )
        drawRect(
            color = color,
            topLeft = Offset(thumbLeft, layout.thumbY),
            size = Size(thumbWidthPx, layout.thumbHeight),
        )
    }
}
