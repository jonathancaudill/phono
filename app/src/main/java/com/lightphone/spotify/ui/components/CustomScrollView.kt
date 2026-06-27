package com.lightphone.spotify.ui.components

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lightphone.spotify.ui.theme.n
import kotlinx.coroutines.launch
import kotlin.math.max

/** How the right-edge scrollbar strip responds to touch. */
enum class ScrollbarMode {
    /** Liked Songs / Albums only: decorative strip; hold to open date/A–Z scrub (no drag-scroll). */
    ScrubHoldOnly,
    /** Default everywhere else: draggable thumb with anchored finger tracking. */
    Grabbable,
}

/**
 * The standard scroll surface, ported from mono's CustomScrollView:
 *
 *  - Overscroll is disabled (`LocalOverscrollConfiguration provides null`) so
 *    list items never stretch/squish at the edges — the out-of-the-box Compose
 *    behavior the brief explicitly calls out. This is the equivalent of React
 *    Native's `overScrollMode="never"`.
 *  - A thin custom scroll indicator is drawn on the right edge instead of the
 *    platform scrollbar (visual spec from light-template: 1px track, 5px thumb).
 *  - [ScrollbarMode.Grabbable] (default): draggable thumb with anchored finger tracking.
 *  - [ScrollbarMode.ScrubHoldOnly]: Liked Songs / Albums only — hold strip for date/A–Z scrub.
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
    /** When set, holding the scrollbar opens the A–Z jump overlay. */
    alphaIndex: LibraryAlphaIndex? = null,
    scrollbarMode: ScrollbarMode = ScrollbarMode.Grabbable,
    onScrubToIndex: suspend (Int) -> Unit = {},
    onScrubJumpChange: (Boolean) -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val scrubIndex = dateIndex?.takeIf { !it.isEmpty }
    val alphaScrubIndex = alphaIndex?.takeIf { !it.isEmpty }
    val scrubController = remember(scrubIndex, alphaScrubIndex) { ScrubController() }

    Box(modifier = modifier.fillMaxSize()) {
        // List + scrollbar — never reads scrubController.overlayOpen (avoids LazyColumn recompose).
        CustomScrollListBody(
            state = state,
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            loadedItemCount = loadedItemCount,
            virtualItemCount = virtualItemCount,
            scrubIndex = scrubIndex,
            alphaScrubIndex = alphaScrubIndex,
            scrollbarMode = scrollbarMode,
            scrubController = scrubController,
            onScrubToIndex = onScrubToIndex,
            onScrubJumpChange = onScrubJumpChange,
            content = content,
        )

        // Scrub visuals isolated — only this subtree recomposes on open/close.
        if (scrubIndex != null) {
            LibraryScrubVisuals(
                controller = scrubController,
                dateIndex = scrubIndex,
                modifier = Modifier.zIndex(2f),
            )
        }
        if (alphaScrubIndex != null) {
            LibraryAlphaScrubVisuals(
                controller = scrubController,
                alphaIndex = alphaScrubIndex,
                modifier = Modifier.zIndex(2f),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CustomScrollListBody(
    state: LazyListState,
    contentPadding: PaddingValues,
    verticalArrangement: Arrangement.Vertical,
    loadedItemCount: Int?,
    virtualItemCount: Int?,
    scrubIndex: LibraryDateIndex?,
    alphaScrubIndex: LibraryAlphaIndex?,
    scrollbarMode: ScrollbarMode,
    scrubController: ScrubController,
    onScrubToIndex: suspend (Int) -> Unit,
    onScrubJumpChange: (Boolean) -> Unit,
    content: LazyListScope.() -> Unit,
) {
    var containerWidthPx by remember { mutableFloatStateOf(0f) }
    var containerHeightPx by remember { mutableFloatStateOf(0f) }

    val scrubEnabled = scrubIndex != null || alphaScrubIndex != null
    val scrubHoldOnly = scrollbarMode == ScrollbarMode.ScrubHoldOnly
    val touchStripWidth = if (scrubHoldOnly) SCRUBBAR_TOUCH_WIDTH else GRABBABLE_STRIP_TOUCH_WIDTH
    val scrollIndex = state.firstVisibleItemIndex
    val minThumbPx = with(LocalDensity.current) { n(20).toPx() }
    val scrollScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val touchStripWidthPx = with(density) { touchStripWidth.toPx() }

    DisposableEffect(Unit) {
        onDispose { onScrubJumpChange(false) }
    }

    val layout by remember {
        derivedStateOf {
            state.computeScrollbarLayout(
                loadedItemCount = loadedItemCount,
                virtualItemCount = virtualItemCount,
                minThumbPx = minThumbPx,
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged {
                containerWidthPx = it.width.toFloat()
                containerHeightPx = it.height.toFloat()
            },
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

        val metrics = layout
        val itemsAvailable = max(
            virtualItemCount ?: 0,
            loadedItemCount ?: state.layoutInfo.totalItemsCount,
        ).coerceAtLeast(state.layoutInfo.totalItemsCount)

        if (scrubHoldOnly && metrics != null && containerHeightPx > 0f) {
            val stripLeftPx = containerWidthPx - touchStripWidthPx

            Box(
                modifier = Modifier
                    .zIndex(3f)
                    .align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .width(touchStripWidth)
                    .pointerInput(
                        scrubIndex,
                        alphaScrubIndex,
                        scrollIndex,
                        loadedItemCount,
                        virtualItemCount,
                        containerWidthPx,
                        containerHeightPx,
                        touchStripWidth,
                    ) {
                        suspend fun AwaitPointerEventScope.runScrubSession(pointerId: PointerId) {
                            var jumpTarget = -1
                            onScrubJumpChange(true)
                            try {
                                if (alphaScrubIndex != null) {
                                    var alphaSelection =
                                        initialAlphaScrubSelection(alphaScrubIndex, scrollIndex)
                                    scrubController.overlayOpen = true
                                    scrubController.alphaSelection = alphaSelection

                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Main)
                                        val pointer =
                                            event.changes.firstOrNull { it.id == pointerId } ?: break
                                        if (!pointer.pressed) break

                                        alphaSelection = updateAlphaScrubSelection(
                                            alphaIndex = alphaScrubIndex,
                                            yPx = pointer.position.y,
                                            heightPx = containerHeightPx,
                                        )
                                        scrubController.alphaSelection = alphaSelection
                                        pointer.consume()
                                    }
                                    jumpTarget = alphaSelection.startIndex
                                } else {
                                    val index = scrubIndex!!
                                    var selection = initialScrubSelection(index, scrollIndex)
                                    scrubController.overlayOpen = true
                                    scrubController.selection = selection

                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Main)
                                        val pointer =
                                            event.changes.firstOrNull { it.id == pointerId } ?: break
                                        if (!pointer.pressed) break

                                        selection = updateScrubSelection(
                                            dateIndex = index,
                                            current = selection,
                                            column = scrubColumnAt(
                                                xPx = stripLeftPx + pointer.position.x,
                                                totalWidthPx = containerWidthPx,
                                                density = density.density,
                                                stripWidthDp = touchStripWidth,
                                            ),
                                            yPx = pointer.position.y,
                                            heightPx = containerHeightPx,
                                        )
                                        scrubController.selection = selection
                                        pointer.consume()
                                    }

                                    val final = selection
                                    if (final.reachedMonthsZone && final.selectedMonth != null) {
                                        jumpTarget = final.selectedMonth.startIndex
                                    }
                                }
                            } finally {
                                scrubController.overlayOpen = false
                                scrubController.selection = null
                                scrubController.alphaSelection = null
                                if (jumpTarget < 0) {
                                    onScrubJumpChange(false)
                                }
                            }

                            if (jumpTarget >= 0) {
                                val target = jumpTarget
                                scrollScope.launch {
                                    withFrameNanos { }
                                    try {
                                        onScrubToIndex(target)
                                    } finally {
                                        onScrubJumpChange(false)
                                    }
                                }
                            }
                        }

                        awaitEachGesture {
                            if (!scrubEnabled) return@awaitEachGesture
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                            longPress.consume()
                            runScrubSession(down.id)
                        }
                    },
            ) {
                MonoGrabbableScrollbar(
                    layout = metrics,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(SCRUBBAR_TOUCH_WIDTH)
                        .padding(end = n(2)),
                )
            }
        } else if (!scrubHoldOnly && itemsAvailable > 0) {
            val scrollbarState = state.monoScrollbarState(itemsAvailable)
            val onThumbMoved = rememberMonoScrollbarScroller(state, itemsAvailable)
            if (scrollbarState.thumbTrackSizePercent > 0f) {
                MonoDraggableScrollbarStrip(
                    state = scrollbarState,
                    onThumbMoved = onThumbMoved,
                    minThumbPx = minThumbPx,
                    modifier = Modifier
                        .zIndex(3f)
                        .align(Alignment.TopEnd),
                )
            }
        }
    }
}
