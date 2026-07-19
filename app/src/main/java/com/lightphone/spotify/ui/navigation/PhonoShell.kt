package com.lightphone.spotify.ui.navigation

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.ContextMenuHost
import com.lightphone.spotify.ui.components.PhonoTabBar
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.consumeScrimTouches
import com.lightphone.spotify.ui.phono.leftEdgeSwipeBack
import com.lightphone.spotify.ui.screens.AlbumDetailScreen
import com.lightphone.spotify.ui.screens.AlbumsScreen
import com.lightphone.spotify.ui.screens.ArtistDetailScreen
import com.lightphone.spotify.ui.screens.CreatePlaylistScreen
import com.lightphone.spotify.ui.screens.DownloadsScreen
import com.lightphone.spotify.ui.screens.LikedSongsScreen
import com.lightphone.spotify.ui.screens.PlayingScreen
import com.lightphone.spotify.ui.screens.PlaylistDetailScreen
import com.lightphone.spotify.ui.screens.PlaylistPickerScreen
import com.lightphone.spotify.ui.screens.PlaylistsScreen
import com.lightphone.spotify.ui.screens.QueueScreen
import com.lightphone.spotify.ui.screens.SearchInputScreen
import com.lightphone.spotify.ui.screens.SearchResultsScreen
import com.lightphone.spotify.ui.screens.SearchScreen
import com.lightphone.spotify.ui.screens.SettingsScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal const val OverlayRoot = "overlay_root"

private data class PhonoShellPlaybackState(
    val loggedIn: Boolean,
    val sessionExpired: Boolean,
    val reconnecting: Boolean,
    val networkOnline: Boolean,
    val statusMessage: String?,
    val error: String?,
)

@Composable
fun PhonoShell(
    vm: AppViewModel,
    shellVm: PhonoShellViewModel = viewModel(),
) {
    val overlayNavController = rememberNavController()
    val overlayNav = rememberOverlayNavigator(overlayNavController)
    // visibleEntries includes the exiting route for the exit frame; currentBackStackEntry does not.
    // Gating on current only collapses NavHost to 0×0 while the detail (and scrollbar) still draw.
    val visibleOverlayEntries by overlayNavController.visibleEntries.collectAsState()
    val shellPlayback by remember(vm) {
        vm.playback.map { p ->
            PhonoShellPlaybackState(
                loggedIn = p.loggedIn,
                sessionExpired = p.sessionExpired,
                reconnecting = p.reconnecting,
                networkOnline = p.networkOnline,
                statusMessage = p.statusMessage,
                error = p.error,
            )
        }.distinctUntilChanged()
    }.collectAsState(
        initial = PhonoShellPlaybackState(
            loggedIn = vm.playback.value.loggedIn,
            sessionExpired = vm.playback.value.sessionExpired,
            reconnecting = vm.playback.value.reconnecting,
            networkOnline = vm.playback.value.networkOnline,
            statusMessage = vm.playback.value.statusMessage,
            error = vm.playback.value.error,
        ),
    )
    val currentTab by shellVm.currentTab.collectAsState()
    val tabs = remember(vm.downloadsSupported) { phonoTabs(includeDownloads = vm.downloadsSupported) }

    LaunchedEffect(shellPlayback.loggedIn) {
        if (!shellPlayback.loggedIn) {
            overlayNav.popToRoot()
        }
    }

    LaunchedEffect(tabs, currentTab) {
        if (currentTab !in tabs) shellVm.selectTab(PhonoTab.Liked)
    }

    val showOverlayLayer = visibleOverlayEntries.any { entry ->
        val route = entry.destination.route?.substringBefore('?')
        route != null && route != OverlayRoot
    }
    val contextMenu by vm.contextMenu.collectAsState()
    val modalOpen = contextMenu.target != null ||
        contextMenu.showCopied ||
        contextMenu.deleteConfirm != null
    val swipeBackEnabled = showOverlayLayer && !modalOpen
    val navbarStatusMessage = when {
        shellPlayback.sessionExpired -> null
        shellPlayback.reconnecting -> "Reconnecting…"
        !shellPlayback.networkOnline -> "Device offline"
        else -> null
    }
    val showSessionBanner = shellPlayback.sessionExpired && shellPlayback.statusMessage != null
    val colors = LightThemeTokens.colors

    BackHandler(enabled = modalOpen || showOverlayLayer) {
        when {
            contextMenu.showCopied -> vm.dismissCopiedOverlay()
            contextMenu.deleteConfirm != null -> vm.cancelDeletePlaylist()
            contextMenu.target != null -> vm.dismissContextMenu()
            showOverlayLayer -> overlayNavController.popBackStack()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        if (showSessionBanner) {
            shellPlayback.statusMessage?.let { msg ->
                LightText(
                    text = msg,
                    variant = LightTextVariant.Detail,
                    color = PhonoSemanticColors.Warning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = legacyNToGridDp(12), vertical = legacyNToGridDp(4)),
                )
            }
        }
        shellPlayback.error?.let { err ->
            if (!showSessionBanner) {
                LightText(
                    text = err,
                    variant = LightTextVariant.Detail,
                    color = PhonoSemanticColors.Error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = legacyNToGridDp(12), vertical = legacyNToGridDp(4)),
                )
            }
        }

        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    when (currentTab) {
                        PhonoTab.Liked -> LikedSongsScreen(
                            vm = vm,
                            onOpenPlaying = { overlayNav.navigate(OverlayDestination.Playing) },
                            onPlayTrack = { index ->
                                vm.playLikedFrom(index)
                                overlayNav.navigate(OverlayDestination.Playing)
                            },
                        )
                        PhonoTab.Albums -> AlbumsScreen(
                            vm = vm,
                            onOpenPlaying = { overlayNav.navigate(OverlayDestination.Playing) },
                            onOpenAlbum = { id, name ->
                                overlayNav.navigate(OverlayDestination.Album(id, name))
                            },
                        )
                        PhonoTab.Playlists -> PlaylistsScreen(
                            vm = vm,
                            onOpenPlaying = { overlayNav.navigate(OverlayDestination.Playing) },
                            onOpenPlaylist = { id, name ->
                                overlayNav.navigate(OverlayDestination.Playlist(id, name))
                            },
                            onCreatePlaylist = {
                                vm.resetCreatePlaylistState()
                                overlayNav.navigate(OverlayDestination.CreatePlaylist)
                            },
                        )
                        PhonoTab.Search -> SearchScreen(
                            vm = vm,
                            onOpenEditor = { query ->
                                overlayNav.navigate(OverlayDestination.SearchInput(query))
                            },
                        )
                        PhonoTab.Downloads -> DownloadsScreen(
                            vm = vm,
                            onOpenPlaying = { overlayNav.navigate(OverlayDestination.Playing) },
                            onPlayTrack = { track ->
                                vm.playTracks(listOf(track), 0, "Downloads")
                                overlayNav.navigate(OverlayDestination.Playing)
                            },
                        )
                        PhonoTab.Settings -> {
                            val activity = LocalContext.current as? ComponentActivity
                            SettingsScreen(
                                vm = vm,
                                onLogout = {
                                    vm.logout {
                                        activity?.recreate()
                                    }
                                },
                            )
                        }
                    }
                }

                PhonoTabBar(
                    tabs = tabs,
                    currentTab = currentTab,
                    onTabSelected = shellVm::selectTab,
                    statusMessage = navbarStatusMessage,
                )
            }

            Box(Modifier.fillMaxSize().zIndex(1f)) {
                if (showOverlayLayer) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(colors.background)
                            .consumeScrimTouches(),
                    )
                }
                // size(0.dp) when idle so touches reach tabs; stay fillMaxSize while any non-root
                // entry is still visible (including the exit frame) so scrollbars keep TopEnd.
                NavHost(
                    navController = overlayNavController,
                    startDestination = OverlayRoot,
                    modifier = if (showOverlayLayer) {
                        Modifier
                            .fillMaxSize()
                            .leftEdgeSwipeBack(
                                enabled = swipeBackEnabled,
                                edgeWidth = 1.5f.gridUnitsAsDp(),
                                distanceThreshold = 3f.gridUnitsAsDp(),
                                onBack = { overlayNavController.popBackStack() },
                            )
                    } else {
                        Modifier.size(0.dp)
                    },
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
                    sizeTransform = { null },
                ) {
                    composable(OverlayRoot) {
                        Box(Modifier.fillMaxSize())
                    }
                    overlayDestinations(
                        vm = vm,
                        overlayNav = overlayNav,
                        overlayNavController = overlayNavController,
                    )
                }
            }

            ContextMenuHost(
                vm = vm,
                onNavigateToPlaylistPicker = { uri ->
                    vm.loadPlaylistPicker(uri)
                    overlayNav.navigate(OverlayDestination.PlaylistPicker(uri))
                },
            )
        }
    }
}

private fun NavGraphBuilder.overlayDestinations(
    vm: AppViewModel,
    overlayNav: OverlayNavigator,
    overlayNavController: NavHostController,
) {
    composable(Routes.Playing) {
        PlayingScreen(
            vm = vm,
            onBack = { overlayNavController.popBackStack() },
            onOpenAlbum = { albumId ->
                overlayNav.navigate(OverlayDestination.Album(albumId))
            },
            onOpenQueue = { overlayNav.navigate(OverlayDestination.Queue) },
            onAddToPlaylist = { uri ->
                vm.loadPlaylistPicker(uri)
                overlayNav.navigate(OverlayDestination.PlaylistPicker(uri))
            },
        )
    }
    composable(Routes.Queue) {
        QueueScreen(
            vm = vm,
            onBack = { overlayNavController.popBackStack() },
        )
    }
    composable(
        route = Routes.SearchInput,
        arguments = listOf(
            navArgument("query") {
                type = NavType.StringType
                defaultValue = ""
            },
        ),
    ) { entry ->
        val initialQuery = entry.arguments?.getString("query").orEmpty()
        SearchInputScreen(
            initialQuery = initialQuery,
            onSubmit = { query ->
                vm.updateSearchQuery(query)
                overlayNavController.popBackStack()
                overlayNav.navigate(OverlayDestination.SearchResults(query))
            },
            onBack = { overlayNavController.popBackStack() },
        )
    }
    composable(
        route = Routes.SearchResults,
        arguments = listOf(navArgument("query") { type = NavType.StringType }),
    ) { entry ->
        val query = entry.arguments?.getString("query").orEmpty()
        SearchResultsScreen(
            vm = vm,
            query = query,
            onBack = { overlayNavController.popBackStack() },
            onOpenAlbum = { id, name ->
                overlayNav.navigate(OverlayDestination.Album(id, name))
            },
            onOpenArtist = { id -> overlayNav.navigate(OverlayDestination.Artist(id)) },
            onPlayTrack = { track ->
                vm.playSearchTrack(track)
                overlayNav.navigate(OverlayDestination.Playing)
            },
            onOpenPlaylist = { id, name ->
                overlayNav.navigate(OverlayDestination.Playlist(id, name))
            },
        )
    }
    composable(
        route = Routes.Album,
        arguments = listOf(
            navArgument("albumId") { type = NavType.StringType },
            navArgument("title") { type = NavType.StringType; defaultValue = "" },
        ),
    ) { entry ->
        val albumId = entry.arguments?.getString("albumId").orEmpty()
        val title = entry.arguments?.getString("title").orEmpty()
        AlbumDetailScreen(
            vm = vm,
            albumId = albumId,
            fallbackTitle = title.ifBlank { "Album" },
            onBack = { overlayNavController.popBackStack() },
            onOpenArtist = { id -> overlayNav.navigate(OverlayDestination.Artist(id)) },
            onPlayTrack = { index ->
                vm.playAlbumFrom(albumId, index)
                overlayNav.navigate(OverlayDestination.Playing)
            },
        )
    }
    composable(
        route = Routes.Playlist,
        arguments = listOf(
            navArgument("playlistId") { type = NavType.StringType },
            navArgument("title") { type = NavType.StringType; defaultValue = "" },
        ),
    ) { entry ->
        val playlistId = entry.arguments?.getString("playlistId").orEmpty()
        val title = entry.arguments?.getString("title").orEmpty()
        PlaylistDetailScreen(
            vm = vm,
            playlistId = playlistId,
            fallbackTitle = title.ifBlank { "Playlist" },
            onBack = { overlayNavController.popBackStack() },
            onPlayTrack = { index ->
                vm.playPlaylistFrom(playlistId, index)
                overlayNav.navigate(OverlayDestination.Playing)
            },
        )
    }
    composable(Routes.CreatePlaylist) {
        CreatePlaylistScreen(
            vm = vm,
            onBack = { overlayNavController.popBackStack() },
            onCreated = { id, name ->
                overlayNavController.popBackStack()
                overlayNav.navigate(OverlayDestination.Playlist(id, name))
            },
        )
    }
    composable(
        route = Routes.PlaylistPicker,
        arguments = listOf(navArgument("trackUri") { type = NavType.StringType }),
    ) { entry ->
        val trackUri = entry.arguments?.getString("trackUri").orEmpty()
        PlaylistPickerScreen(
            vm = vm,
            trackUri = trackUri,
            onBack = { overlayNavController.popBackStack() },
            onCreatePlaylist = {
                vm.resetCreatePlaylistState()
                overlayNav.navigate(OverlayDestination.CreatePlaylist)
            },
            onAdded = { overlayNavController.popBackStack() },
        )
    }
    composable(
        route = Routes.Artist,
        arguments = listOf(navArgument("artistId") { type = NavType.StringType }),
    ) { entry ->
        val artistId = entry.arguments?.getString("artistId").orEmpty()
        ArtistDetailScreen(
            vm = vm,
            artistId = artistId,
            onBack = { overlayNavController.popBackStack() },
            onOpenAlbum = { id, name ->
                overlayNav.navigate(OverlayDestination.Album(id, name))
            },
            onPlayTopTrack = { index ->
                vm.playArtistTopTrack(index)
                overlayNav.navigate(OverlayDestination.Playing)
            },
        )
    }
}
