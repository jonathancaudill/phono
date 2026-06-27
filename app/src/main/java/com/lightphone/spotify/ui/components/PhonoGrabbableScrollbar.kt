package com.lightphone.spotify.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.lightphone.spotify.ui.theme.PhonoColors
import com.lightphone.spotify.ui.theme.n
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

/**
 * Scrollbar geometry for [CustomScrollView] scrub-hold mode (decorative thumb).
 *
 * Grabbable drag uses [PhonoScrollbarState] + [PhonoDraggableScrollbarStrip] instead.
 */
data class ScrollbarLayout(
    val thumbHeight: Float,
    val thumbY: Float,
    val contentHeight: Float,
    val viewport: Float,
    val avgItemSize: Float,
    val itemTotal: Int,
) {
    val thumbTravel: Float get() = (viewport - thumbHeight).coerceAtLeast(0f)
    val maxScrollPx: Float get() = (contentHeight - viewport).coerceAtLeast(0f)
}

/** Percent-based scrollbar state (NowInAndroid / LazyColumn fast-scroll pattern). */
data class PhonoScrollbarState(
    val thumbSizePercent: Float = 1f,
    val thumbMovedPercent: Float = 0f,
) {
    val thumbTrackSizePercent: Float get() = (1f - thumbSizePercent).coerceAtLeast(0f)
}

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

    val scrollPx = firstVisibleItemIndex * avgItemSize + firstVisibleItemScrollOffset
    val thumbHeight = max((viewport * viewport) / contentHeight, minThumbPx)
    val maxScroll = contentHeight - viewport
    val thumbTravel = viewport - thumbHeight
    val thumbY = if (maxScroll > 0f && thumbTravel > 0f) {
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
 * Derives [PhonoScrollbarState] from list scroll position with sub-item interpolation
 * so the thumb moves smoothly between rows (NowInAndroid scrollbarState).
 */
@Composable
fun LazyListState.phonoScrollbarState(itemsAvailable: Int): PhonoScrollbarState {
    var state by remember { mutableStateOf(PhonoScrollbarState()) }
    LaunchedEffect(this, itemsAvailable) {
        snapshotFlow {
            if (itemsAvailable <= 0) return@snapshotFlow null

            val visibleItemsInfo = layoutInfo.visibleItemsInfo
            if (visibleItemsInfo.isEmpty()) return@snapshotFlow null

            val firstIndex = min(
                a = interpolateFirstItemIndex(visibleItemsInfo),
                b = itemsAvailable.toFloat(),
            )
            if (firstIndex.isNaN()) return@snapshotFlow null

            val itemsVisible = visibleItemsInfo.sumOf { itemInfo ->
                itemVisibilityPercentage(
                    itemSize = itemInfo.size,
                    itemStartOffset = itemInfo.offset,
                    viewportStartOffset = layoutInfo.viewportStartOffset,
                    viewportEndOffset = layoutInfo.viewportEndOffset,
                ).toDouble()
            }.toFloat()

            PhonoScrollbarState(
                thumbSizePercent = min(itemsVisible / itemsAvailable, 1f),
                thumbMovedPercent = min(firstIndex / itemsAvailable, 1f),
            )
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { state = it }
    }
    return state
}

/**
 * Bridges thumb drag percentage back to [LazyListState.scrollToItem].
 * Uses LaunchedEffect so scroll runs in a normal coroutine scope, not pointerInput.
 */
@Composable
fun rememberPhonoScrollbarScroller(
    listState: LazyListState,
    itemsAvailable: Int,
): (Float) -> Unit {
    var thumbMovedPercent by remember { mutableFloatStateOf(Float.NaN) }
    val itemCount by rememberUpdatedState(itemsAvailable)
    val state by rememberUpdatedState(listState)

    LaunchedEffect(state, thumbMovedPercent) {
        if (thumbMovedPercent.isNaN()) return@LaunchedEffect
        val totalItems = state.layoutInfo.totalItemsCount
        if (totalItems <= 0) return@LaunchedEffect

        val maxLazyIndex = (totalItems - 1).coerceAtLeast(0)
        val exactPosition = itemCount * thumbMovedPercent
        val targetIndex = exactPosition.toInt().coerceIn(0, maxLazyIndex)
        val remainder = exactPosition - targetIndex

        val avgItemSize = state.layoutInfo.visibleItemsInfo
            .map { it.size }
            .average()
            .toFloat()
            .takeIf { it > 0f }
        val scrollOffset = if (avgItemSize != null) {
            (remainder * avgItemSize).toInt().coerceAtLeast(0)
        } else {
            0
        }
        state.scrollToItem(targetIndex, scrollOffset)
    }

    return remember { { percent: Float -> thumbMovedPercent = percent } }
}

/**
 * Right-edge grabbable scrollbar: wide touch strip, narrow visual track.
 * Drag uses relative motion anchored to the thumb (does not jump to finger on grab).
 */
@Composable
fun PhonoDraggableScrollbarStrip(
    state: PhonoScrollbarState,
    onThumbMoved: (Float) -> Unit,
    minThumbPx: Float,
    modifier: Modifier = Modifier,
    touchWidth: Dp = SCROLLBAR_SCREEN_GUTTER,
) {
    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    var dragThumbMovedPercent by remember { mutableFloatStateOf(Float.NaN) }

    val currentState = rememberUpdatedState(state)
    val movedPercent = if (dragThumbMovedPercent.isNaN()) {
        state.thumbMovedPercent
    } else {
        dragThumbMovedPercent
    }

    val thumbSizePx = max(state.thumbSizePercent * trackHeightPx, minThumbPx)
    val thumbTravelPercent = movedPercent.coerceIn(0f, state.thumbTrackSizePercent)
    val trackSizePx = if (state.thumbTrackSizePercent > 0f) {
        (trackHeightPx - thumbSizePx) / state.thumbTrackSizePercent
    } else {
        trackHeightPx
    }
    val thumbYPx = trackSizePx * thumbTravelPercent

    Box(
        modifier = modifier
            .width(touchWidth)
            .fillMaxHeight()
            .onSizeChanged { trackHeightPx = it.height.toFloat() }
            .pointerInput(Unit) {
                var dragThumbCenterY = Float.NaN
                detectVerticalDragGestures(
                    onDragStart = {
                        val s = currentState.value
                        val sizePx = max(s.thumbSizePercent * trackHeightPx, minThumbPx)
                        val travelPx = (trackHeightPx - sizePx).coerceAtLeast(0f)
                        val travelPercent = s.thumbMovedPercent.coerceIn(0f, s.thumbTrackSizePercent)
                        val trackSizePx = if (s.thumbTrackSizePercent > 0f) {
                            travelPx / s.thumbTrackSizePercent
                        } else {
                            trackHeightPx
                        }
                        val yPx = trackSizePx * travelPercent
                        dragThumbCenterY = yPx + sizePx / 2f
                    },
                    onDragEnd = {
                        dragThumbCenterY = Float.NaN
                        dragThumbMovedPercent = Float.NaN
                    },
                    onDragCancel = {
                        dragThumbCenterY = Float.NaN
                        dragThumbMovedPercent = Float.NaN
                    },
                    onVerticalDrag = { change, dragAmount ->
                        if (dragThumbCenterY.isNaN() || trackHeightPx <= 0f) return@detectVerticalDragGestures
                        dragThumbCenterY += dragAmount
                        val s = currentState.value
                        val sizePx = max(s.thumbSizePercent * trackHeightPx, minThumbPx)
                        val travelPx = (trackHeightPx - sizePx).coerceAtLeast(0f)
                        if (travelPx <= 0f) return@detectVerticalDragGestures
                        val travelFraction =
                            ((dragThumbCenterY - sizePx / 2f) / travelPx).coerceIn(0f, 1f)
                        dragThumbMovedPercent = travelFraction
                        onThumbMoved(travelFraction)
                        change.consume()
                    },
                )
            },
    ) {
        PhonoGrabbableScrollbarVisual(
            thumbY = thumbYPx,
            thumbHeight = thumbSizePx,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(SCRUBBAR_TOUCH_WIDTH)
                .padding(end = n(2)),
        )
    }
}

@Composable
private fun PhonoGrabbableScrollbarVisual(
    thumbY: Float,
    thumbHeight: Float,
    modifier: Modifier = Modifier,
    trackWidth: Dp = n(1),
    thumbWidth: Dp = n(5),
    thumbRightOverhang: Dp = n(2),
    color: androidx.compose.ui.graphics.Color = PhonoColors.Foreground,
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
            topLeft = Offset(thumbLeft, thumbY),
            size = Size(thumbWidthPx, thumbHeight),
        )
    }
}

/** Decorative thumb for scrub-hold lists (Liked Songs / Albums). */
@Composable
fun PhonoGrabbableScrollbar(
    layout: ScrollbarLayout,
    modifier: Modifier = Modifier,
    trackWidth: Dp = n(1),
    thumbWidth: Dp = n(5),
    thumbRightOverhang: Dp = n(2),
    color: androidx.compose.ui.graphics.Color = PhonoColors.Foreground,
) {
    PhonoGrabbableScrollbarVisual(
        thumbY = layout.thumbY,
        thumbHeight = layout.thumbHeight,
        modifier = modifier,
        trackWidth = trackWidth,
        thumbWidth = thumbWidth,
        thumbRightOverhang = thumbRightOverhang,
        color = color,
    )
}

private fun LazyListState.interpolateFirstItemIndex(
    visibleItems: List<LazyListItemInfo>,
): Float {
    if (visibleItems.isEmpty()) return 0f

    val firstItem = visibleItems.first()
    val firstItemIndex = firstItem.index
    if (firstItemIndex < 0) return Float.NaN

    val firstItemSize = firstItem.size
    if (firstItemSize == 0) return Float.NaN

    val offsetPercentage = abs(firstItem.offset.toFloat()) / firstItemSize
    val nextItem = visibleItems.getOrNull(1) ?: return firstItemIndex + offsetPercentage
    val nextItemIndex = nextItem.index

    return firstItemIndex + ((nextItemIndex - firstItemIndex) * offsetPercentage)
}

private fun itemVisibilityPercentage(
    itemSize: Int,
    itemStartOffset: Int,
    viewportStartOffset: Int,
    viewportEndOffset: Int,
): Float {
    if (itemSize == 0) return 0f
    val itemEnd = itemStartOffset + itemSize
    val startOffset = when {
        itemStartOffset > viewportStartOffset -> 0
        else -> abs(abs(viewportStartOffset) - abs(itemStartOffset))
    }
    val endOffset = when {
        itemEnd < viewportEndOffset -> 0
        else -> abs(abs(itemEnd) - abs(viewportEndOffset))
    }
    return (itemSize - startOffset - endOffset).toFloat() / itemSize.toFloat()
}
