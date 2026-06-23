package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.lightphone.spotify.data.SearchFilter
import com.lightphone.spotify.data.SearchResultItem
import com.lightphone.spotify.data.SearchResults
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.CustomScrollView
import com.lightphone.spotify.ui.components.MonoContentContainer
import com.lightphone.spotify.ui.components.MonoFallbackImage
import com.lightphone.spotify.ui.components.StyledText
import com.lightphone.spotify.ui.components.tap
import com.lightphone.spotify.ui.theme.MonoColors
import com.lightphone.spotify.ui.theme.n

@Composable
fun SearchResultsScreen(
    vm: AppViewModel,
    query: String,
    onBack: () -> Unit,
    onOpenAlbum: (String, String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onPlayTrack: (SearchResultItem.Track) -> Unit,
    onPlayPlaylist: (String) -> Unit,
) {
    val state by vm.search.collectAsState()

    LaunchedEffect(query) {
        vm.submitSearch(query)
    }

    MonoContentContainer(
        title = "Results for $query",
        hideBackButton = false,
        onBack = onBack,
        rightIconVisible = false,
        horizontalPadding = n(20),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = n(20)),
        ) {
            SearchFilterChips(
                selected = state.filter,
                onSelect = vm::setSearchFilter,
            )
            Spacer(Modifier.size(n(12)))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val display = state.results?.displayFor(state.filter)
                when {
                    state.loading && state.results == null -> Unit
                    state.error != null && display.isNullOrEmpty() ->
                        EmptyListMessage(state.error!!)
                    display.isNullOrEmpty() ->
                        EmptyListMessage("No results found for \"$query\".")
                    else -> {
                        val results = state.results ?: return@Box
                        val items = results.displayFor(state.filter)
                        CustomScrollView(verticalArrangement = Arrangement.spacedBy(n(8))) {
                            items(items, key = { "${it::class.simpleName}-${it.id}" }) { item ->
                                SearchResultRow(item) {
                                    vm.openSearchResult(
                                        item,
                                        onOpenAlbum,
                                        onOpenArtist,
                                        onPlayTrack,
                                        onPlayPlaylist,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun SearchResults.displayFor(filter: SearchFilter): List<SearchResultItem> {
    val (top, rest) = itemsForFilter(filter)
    return buildList {
        if (filter == SearchFilter.All) top?.let { add(it) }
        addAll(rest)
    }
}

@Composable
private fun SearchFilterChips(
    selected: SearchFilter,
    onSelect: (SearchFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(n(8)),
    ) {
        SearchFilter.entries.forEach { filter ->
            val active = filter == selected
            Box(
                modifier = Modifier
                    .background(if (active) MonoColors.Foreground else MonoColors.PlaceholderBg)
                    .tap { onSelect(filter) }
                    .padding(horizontal = n(14), vertical = n(8)),
            ) {
                StyledText(
                    filter.label,
                    size = 16,
                    lineHeight = 18,
                    color = if (active) MonoColors.Background else MonoColors.Foreground,
                )
            }
        }
    }
}

@Composable
private fun SearchResultRow(item: SearchResultItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = n(50))
            .tap(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoFallbackImage(
            imageUrl = item.imageUrl,
            placeholderText = "?",
            placeholderIconSize = n(24),
            modifier = Modifier.size(n(50)),
        )
        Spacer(Modifier.width(n(15)))
        Column(
            Modifier
                .weight(1f)
                .padding(end = n(10)),
        ) {
            StyledText(
                item.title,
                size = 22,
                lineHeight = 24,
                color = MonoColors.Foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StyledText(
                item.subtitle,
                size = 16,
                lineHeight = 18,
                color = MonoColors.Foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
