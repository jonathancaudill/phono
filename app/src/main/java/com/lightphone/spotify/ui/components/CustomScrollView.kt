package com.lightphone.spotify.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.lightphone.spotify.ui.theme.MonoColors
import com.lightphone.spotify.ui.theme.n
import kotlin.math.max

/**
 * The standard scroll surface, ported from mono's CustomScrollView:
 *
 *  - Overscroll is disabled (`LocalOverscrollConfiguration provides null`) so
 *    list items never stretch/squish at the edges — the out-of-the-box Compose
 *    behavior the brief explicitly calls out. This is the equivalent of React
 *    Native's `overScrollMode="never"`.
 *  - A thin custom scroll indicator is drawn on the right edge instead of the
 *    platform scrollbar.
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
    content: LazyListScope.() -> Unit,
) {
    Box(modifier.fillMaxSize()) {
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
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .padding(start = n(2)),
        )
    }
}

private data class ScrollbarMetrics(val thumbFraction: Float, val positionFraction: Float)

@Composable
private fun MonoScrollbar(
    state: LazyListState,
    loadedItemCount: Int? = null,
    virtualItemCount: Int? = null,
    modifier: Modifier = Modifier,
) {
    val metrics by remember {
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

            // Map physical scroll to virtual library position; cap at last loaded row
            // so the footer runway does not skew the thumb.
            val cappedIndex = minOf(state.firstVisibleItemIndex, max(loaded - 1, 0))
            val scrolled =
                (cappedIndex * itemStride + state.firstVisibleItemScrollOffset).toFloat()
            val maxVirtualScroll = estVirtualContent - viewport
            ScrollbarMetrics(
                thumbFraction = (viewport / estVirtualContent).coerceIn(0f, 1f),
                positionFraction = (scrolled / maxVirtualScroll).coerceIn(0f, 1f),
            )
        }
    }

    val m = metrics ?: return
    val thumbColor = MonoColors.Foreground

    Canvas(modifier.width(n(5))) {
        val trackHeight = size.height
        val lineWidth = n(1).toPx()
        val minThumb = n(20).toPx()
        val thumbHeight = max(trackHeight * m.thumbFraction, minThumb)
        val thumbY = m.positionFraction * (trackHeight - thumbHeight)

        // Faint full-height track (1px) at the right edge.
        drawRect(
            color = thumbColor.copy(alpha = 0.25f),
            topLeft = Offset(size.width - lineWidth, 0f),
            size = Size(lineWidth, trackHeight),
        )
        // Thumb (5px wide).
        drawRect(
            color = thumbColor,
            topLeft = Offset(0f, thumbY),
            size = Size(size.width, thumbHeight),
        )
    }
}
