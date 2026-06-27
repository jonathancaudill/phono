package com.lightphone.spotify.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.lightphone.spotify.ui.theme.PhonoColors
import com.lightphone.spotify.ui.theme.n
import kotlinx.coroutines.flow.distinctUntilChanged

/** Trigger buffer fill when this many rows from the loaded edge are visible. */
const val LIBRARY_PREFETCH_DISTANCE = 60

/**
 * While scrolling near the loaded edge, ask the ViewModel to fetch ahead so flings
 * do not hit a physical LazyColumn wall.
 */
@Composable
fun LibraryListScrollAnchor(
    listState: LazyListState,
    loadedItemCount: Int,
    canLoadMore: Boolean,
    onEnsureBufferAhead: (lastVisibleIndex: Int) -> Unit,
) {
    LaunchedEffect(listState, loadedItemCount, canLoadMore) {
        if (loadedItemCount <= 0) return@LaunchedEffect

        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (!canLoadMore || lastVisible < 0) return@collect
                if (lastVisible >= loadedItemCount - LIBRARY_PREFETCH_DISTANCE) {
                    onEnsureBufferAhead(lastVisible)
                }
            }
    }
}

/** Fixed one-screen runway at the loaded edge while more pages are in flight. */
@Composable
fun LazyItemScope.LibraryListRunway(appending: Boolean) {
    val runwayMinHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = runwayMinHeight),
        contentAlignment = Alignment.Center,
    ) {
        if (appending) {
            CircularProgressIndicator(color = PhonoColors.Foreground)
        }
    }
}
