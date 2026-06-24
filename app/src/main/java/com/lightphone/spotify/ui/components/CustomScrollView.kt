package com.lightphone.spotify.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.lightphone.spotify.ui.theme.MonoColors
import com.lightphone.spotify.ui.theme.n
import kotlin.math.max
import kotlinx.coroutines.flow.first as flowFirst

/**
 * The standard scroll surface, ported from mono's CustomScrollView:
 *
 *  - Overscroll is disabled (`LocalOverscrollConfiguration provides null`) so
 *    list items never stretch/squish at the edges — the out-of-the-box Compose
 *    behavior the brief explicitly calls out. This is the equivalent of React
 *    Native's `overScrollMode="never"`.
 *  - A thin custom scroll indicator is drawn on the right edge instead of the
 *    platform scrollbar.
 *  - Optional library date scrubber: hold the scrollbar, drag left into years,
 *    then months, release to jump.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CustomScrollView(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    /** Loaded row count (excludes footer). Caps scrollbar position mapping. */
    loadedItemCount: Int? = null,
    /** When set, scrollbar thumb size reflects this total instead of loaded item count. */
    virtualItemCount: Int? = null,
    /** True while sync still has unfetched pages (runway footer present). */
    hasMoreItems: Boolean = false,
    /** When set, holding the scrollbar opens the year/month jump overlay. */
    dateIndex: LibraryDateIndex? = null,
    onScrubToIndex: suspend (Int) -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    var scrubActive by remember { mutableStateOf(false) }
    var scrubSelection by remember { mutableStateOf<ScrubSelectionState?>(null) }
    var pendingScrubTarget by remember { mutableIntStateOf(-1) }
    var scrollbarAnchorIndex by remember { mutableIntStateOf(-1) }

    val scrubEnabled = dateIndex != null && !dateIndex.isEmpty
    val scrollIndex = state.firstVisibleItemIndex
    val anchorIndex = scrollbarAnchorIndex.takeIf { it >= 0 }

    LaunchedEffect(pendingScrubTarget) {
        if (pendingScrubTarget < 0) return@LaunchedEffect
        val target = pendingScrubTarget
        pendingScrubTarget = -1

        // Wait until LazyColumn has composed rows through the target index.
        snapshotFlow {
            val runway = if (hasMoreItems) 1 else 0
            state.layoutInfo.totalItemsCount - runway
        }.flowFirst { it > target }

        onScrubToIndex(target)

        snapshotFlow { state.firstVisibleItemIndex }.flowFirst { it >= target - 2 }
        scrollbarAnchorIndex = -1
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (scrubEnabled) {
                    Modifier.pointerInput(dateIndex, scrollIndex) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val barLeft = size.width - SCRUBBAR_TOUCH_WIDTH.toPx()
                            if (down.position.x < barLeft) return@awaitEachGesture

                            val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                            longPress.consume()

                            val index = dateIndex ?: return@awaitEachGesture
                            var selection = initialScrubSelection(index, scrollIndex)
                            scrubSelection = selection
                            scrubActive = true

                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!pointer.pressed) break

                                val column = scrubColumnAt(
                                    xPx = pointer.position.x,
                                    totalWidthPx = size.width.toFloat(),
                                    density = density,
                                )
                                selection = updateScrubSelection(
                                    dateIndex = index,
                                    current = selection,
                                    column = column,
                                    yPx = pointer.position.y,
                                    heightPx = size.height.toFloat(),
                                )
                                scrubSelection = selection
                                pointer.consume()
                            }

                            val final = selection
                            if (final.reachedMonthsZone && final.selectedMonth != null) {
                                val targetIndex = final.selectedMonth.startIndex
                                scrollbarAnchorIndex = targetIndex
                                pendingScrubTarget = targetIndex
                            }
                            scrubActive = false
                            scrubSelection = null
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = state,
                contentPadding = contentPadding,
                verticalArrangement = verticalArrangement,
                content = content,
            )
        }

        MonoScrollbar(
            state = state,
            loadedItemCount = loadedItemCount,
            virtualItemCount = virtualItemCount,
            anchorScrollIndex = anchorIndex,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(SCRUBBAR_TOUCH_WIDTH)
                .padding(start = n(2)),
        )

        if (scrubActive && scrubSelection != null && dateIndex != null) {
            val selection = scrubSelection!!
            LibraryDateScrubOverlay(
                dateIndex = dateIndex,
                selectedYear = selection.selectedYear,
                selectedMonth = selection.selectedMonth,
                monthsInYear = dateIndex.monthsForYear(selection.selectedYear),
            )
        }
    }
}

private data class ScrollbarMetrics(val thumbFraction: Float, val positionFraction: Float)

@Composable
private fun MonoScrollbar(
    state: LazyListState,
    loadedItemCount: Int? = null,
    virtualItemCount: Int? = null,
    anchorScrollIndex: Int? = null,
    modifier: Modifier = Modifier,
) {
    // Re-create derivedState when anchor changes; derivedStateOf inside tracks scroll.
    val metrics by remember(anchorScrollIndex, loadedItemCount, virtualItemCount) {
        derivedStateOf {
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) return@derivedStateOf null

            val layoutTotal = info.totalItemsCount
            val loaded = loadedItemCount ?: layoutTotal
            val virtualTotal = max(virtualItemCount ?: loaded, loaded)
            val spacing = info.mainAxisItemSpacing
            val avgItem = visible.sumOf { it.size } / visible.size
            val itemStride = avgItem + spacing
            val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
            val estVirtualContent = (itemStride * virtualTotal - spacing).toFloat()
            if (estVirtualContent <= viewport || viewport <= 0f) return@derivedStateOf null

            val listIndex = minOf(state.firstVisibleItemIndex, max(loaded - 1, 0))
            val scrollIndex = anchorScrollIndex?.coerceIn(0, max(virtualTotal - 1, 0)) ?: listIndex
            val scrolled = if (anchorScrollIndex != null) {
                scrollIndex * itemStride.toFloat()
            } else {
                (listIndex * itemStride + state.firstVisibleItemScrollOffset).toFloat()
            }
            val maxVirtualScroll = estVirtualContent - viewport
            ScrollbarMetrics(
                thumbFraction = (viewport / estVirtualContent).coerceIn(0f, 1f),
                positionFraction = (scrolled / maxVirtualScroll).coerceIn(0f, 1f),
            )
        }
    }

    val m = metrics ?: return
    val thumbColor = MonoColors.Foreground

    Box(modifier) {
        Canvas(
            Modifier
                .width(n(5))
                .align(Alignment.TopEnd),
        ) {
            val trackHeight = size.height
            val lineWidth = n(1).toPx()
            val minThumb = n(20).toPx()
            val thumbHeight = max(trackHeight * m.thumbFraction, minThumb)
            val thumbY = m.positionFraction * (trackHeight - thumbHeight)

            drawRect(
                color = thumbColor.copy(alpha = 0.25f),
                topLeft = Offset(size.width - lineWidth, 0f),
                size = Size(lineWidth, trackHeight),
            )
            drawRect(
                color = thumbColor,
                topLeft = Offset(0f, thumbY),
                size = Size(size.width, thumbHeight),
            )
        }
    }
}
