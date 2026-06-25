package com.lightphone.spotify.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lightphone.spotify.ui.theme.n

/**
 * Text-only library list: LazyColumn rows = loaded Room items only.
 * Virtual scrollbar reflects [remoteTotal]; a one-screen footer runway softens the loaded edge.
 */
@Composable
fun <T> LibraryInfiniteList(
    listState: LazyListState,
    items: List<T>,
    remoteTotal: Int,
    hasMore: Boolean,
    appending: Boolean,
    canLoadMore: Boolean,
    itemKey: (T) -> String,
    onEnsureBufferAhead: (lastVisibleIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    dateIndex: LibraryDateIndex? = null,
    onScrubToIndex: suspend (Int) -> Unit = {},
    onScrubJumpChange: (Boolean) -> Unit = {},
    itemContent: @Composable (index: Int, item: T) -> Unit,
) {
    LibraryListScrollAnchor(
        listState = listState,
        loadedItemCount = items.size,
        canLoadMore = canLoadMore,
        onEnsureBufferAhead = onEnsureBufferAhead,
    )

    CustomScrollView(
        modifier = modifier,
        state = listState,
        verticalArrangement = Arrangement.spacedBy(n(8)),
        loadedItemCount = items.size,
        virtualItemCount = remoteTotal.takeIf { it > 0 },
        hasMoreItems = hasMore,
        dateIndex = dateIndex,
        onScrubToIndex = onScrubToIndex,
        onScrubJumpChange = onScrubJumpChange,
    ) {
        items(items.size, key = { itemKey(items[it]) }) { index ->
            itemContent(index, items[index])
        }
        if (hasMore) {
            item(key = "library-runway") {
                LibraryListRunway(appending = appending)
            }
        }
    }
}
