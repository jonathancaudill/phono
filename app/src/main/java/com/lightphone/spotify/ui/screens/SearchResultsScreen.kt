package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import com.lightphone.spotify.data.SearchFilter
import com.lightphone.spotify.data.SearchResultItem
import com.lightphone.spotify.data.SearchResults
import com.lightphone.spotify.data.toMetadata
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.CustomScrollView
import com.lightphone.spotify.ui.components.PhonoSwipeToActionRow
import com.lightphone.spotify.ui.components.tapWithLongPress
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun SearchResultsScreen(
    vm: AppViewModel,
    query: String,
    onBack: () -> Unit,
    onOpenAlbum: (String, String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onPlayTrack: (SearchResultItem.Track) -> Unit,
    onOpenPlaylist: (String, String) -> Unit,
) {
    val state by vm.search.collectAsState()

    LaunchedEffect(query) {
        vm.submitSearch(query)
    }

    PhonoScreenShell(
        title = "Results for $query",
        hideBackButton = false,
        onBack = onBack,
        rightIconVisible = false,
        horizontalPadding = legacyNToGridDp(20),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = legacyNToGridDp(20)),
        ) {
            SearchFilterChips(
                selected = state.filter,
                onSelect = vm::setSearchFilter,
            )
            Spacer(Modifier.size(legacyNToGridDp(12)))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val display = state.displayResults?.displayFor(state.filter)
                when {
                    state.error != null && display.isNullOrEmpty() ->
                        EmptyListMessage(state.error!!)
                    state.initialLoading && display.isNullOrEmpty() ->
                        EmptyListMessage("Searching…")
                    state.isEmpty ->
                        EmptyListMessage("No results found for \"$query\".")
                    display.isNullOrEmpty() ->
                        EmptyListMessage("Searching…")
                    else -> {
                        val results = state.displayResults ?: return@Box
                        val items = results.displayFor(state.filter)
                        Column(Modifier.fillMaxSize()) {
                            if (state.refreshing) {
                                LightText(
                                    text = "Searching…",
                                    variant = LightTextVariant.Detail,
                                    color = PhonoSemanticColors.Placeholder,
                                    modifier = Modifier.padding(bottom = legacyNToGridDp(8)),
                                )
                            }
                            state.refreshError?.let { message ->
                                LibraryPartialSyncBanner(message)
                                Spacer(Modifier.size(legacyNToGridDp(8)))
                            }
                            CustomScrollView(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(legacyNToGridDp(8)),
                            ) {
                            items(items, key = { "${it::class.simpleName}-${it.id}" }) { item ->
                                when (item) {
                                    is SearchResultItem.Track -> PhonoSwipeToActionRow(
                                        onSwipeAction = {
                                            vm.addTrackToQueue(item.track.toMetadata())
                                        },
                                    ) {
                                        SearchResultRow(
                                            item = item,
                                            onClick = {
                                                vm.openSearchResult(
                                                    item,
                                                    onOpenAlbum,
                                                    onOpenArtist,
                                                    onPlayTrack,
                                                    onOpenPlaylist,
                                                )
                                            },
                                            onLongClick = {
                                                vm.showTrackContextMenu(item.uri, item.id)
                                            },
                                        )
                                    }
                                    is SearchResultItem.Album -> SearchResultRow(
                                        item = item,
                                        onClick = {
                                            vm.openSearchResult(
                                                item,
                                                onOpenAlbum,
                                                onOpenArtist,
                                                onPlayTrack,
                                                onOpenPlaylist,
                                            )
                                        },
                                        onLongClick = {
                                            vm.showAlbumContextMenu(item.id, item.uri)
                                        },
                                    )
                                    is SearchResultItem.Playlist -> SearchResultRow(
                                        item = item,
                                        onClick = {
                                            vm.openSearchResult(
                                                item,
                                                onOpenAlbum,
                                                onOpenArtist,
                                                onPlayTrack,
                                                onOpenPlaylist,
                                            )
                                        },
                                        onLongClick = {
                                            vm.showPlaylistContextMenu(
                                                playlistId = item.id,
                                                uri = item.uri,
                                                ownerId = item.playlist.owner?.id.orEmpty(),
                                            )
                                        },
                                    )
                                    else -> SearchResultRow(
                                        item = item,
                                        onClick = {
                                            vm.openSearchResult(
                                                item,
                                                onOpenAlbum,
                                                onOpenArtist,
                                                onPlayTrack,
                                                onOpenPlaylist,
                                            )
                                        },
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
    val chipGap = legacyNToGridDp(8)

    SubcomposeLayout(modifier = Modifier.fillMaxWidth()) { constraints ->
        val rowPlaceable = subcompose("chips") {
            Row(horizontalArrangement = Arrangement.spacedBy(chipGap)) {
                SearchFilter.entries.forEach { filter ->
                    SearchFilterChip(
                        filter = filter,
                        active = filter == selected,
                        onSelect = onSelect,
                    )
                }
            }
        }.first().measure(Constraints())

        val scale = min(1f, constraints.maxWidth.toFloat() / rowPlaceable.width)
        val scaledWidth = (rowPlaceable.width * scale).roundToInt()
        val scaledHeight = (rowPlaceable.height * scale).roundToInt()
        val offsetX = ((constraints.maxWidth - scaledWidth) / 2f).roundToInt()

        layout(constraints.maxWidth, scaledHeight) {
            rowPlaceable.placeRelativeWithLayer(offsetX, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

@Composable
private fun SearchFilterChip(
    filter: SearchFilter,
    active: Boolean,
    onSelect: (SearchFilter) -> Unit,
) {
    val colors = LightThemeTokens.colors
    Box(
        modifier = Modifier
            .background(if (active) colors.content else PhonoSemanticColors.PlaceholderBg)
            .lightClickable { onSelect(filter) }
            .padding(horizontal = legacyNToGridDp(14), vertical = legacyNToGridDp(8)),
    ) {
        LightText(
            text = filter.label,
            variant = LightTextVariant.Detail,
            color = if (active) colors.background else colors.content,
            maxLines = 1,
        )
    }
}

@Composable
private fun SearchResultRow(
    item: SearchResultItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = legacyNToGridDp(50))
            .tapWithLongPress(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(end = legacyNToGridDp(10)),
        ) {
            LightText(
                text = item.title,
                variant = LightTextVariant.Copy,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LightText(
                text = item.subtitle,
                variant = LightTextVariant.Detail,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
