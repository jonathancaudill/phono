package com.lightphone.spotify.ui.navigation

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.ContextMenuHost
import com.lightphone.spotify.ui.components.PhonoTabBar
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.consumeScrimTouches
import com.lightphone.spotify.ui.screens.AlbumDetailScreen
import com.lightphone.spotify.ui.screens.AlbumsScreen
import com.lightphone.spotify.ui.screens.ArtistDetailScreen
import com.lightphone.spotify.ui.screens.CreatePlaylistScreen
import com.lightphone.spotify.ui.screens.LikedSongsScreen
import com.lightphone.spotify.ui.screens.PlayingScreen
import com.lightphone.spotify.ui.screens.PlaylistDetailScreen
import com.lightphone.spotify.ui.screens.PlaylistPickerScreen
import com.lightphone.spotify.ui.screens.PlaylistsScreen
import com.lightphone.spotify.ui.screens.QueueScreen
import com.lightphone.spotify.ui.screens.SearchResultsScreen
import com.lightphone.spotify.ui.screens.SearchScreen
import com.lightphone.spotify.ui.screens.SettingsScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens

internal const val OverlayRoot = "overlay_root"

@Composable
fun PhonoShell(
    vm: AppViewModel,
    shellVm: PhonoShellViewModel = viewModel(),
) {
    val overlayNavController = rememberNavController()
    val overlayNav = rememberOverlayNavigator(overlayNavController)
    val overlayBackStackEntry by overlayNavController.currentBackStackEntryAsState()
    val overlayRoute = overlayBackStackEntry?.destination?.route?.substringBefore('?')
    val playback by vm.playback.collectAsState()
    val currentTab by shellVm.currentTab.collectAsState()

    LaunchedEffect(playback.loggedIn) {
        if (!playback.loggedIn) {
            overlayNav.popToRoot()
        }
    }

    val showOverlayLayer = overlayRoute != null && overlayRoute != OverlayRoot
    val navbarStatusMessage = when {
        playback.sessionExpired -> null
        playback.reconnecting -> "Reconnecting…"
        !playback.networkOnline -> "Device offline"
        else -> null
    }
    val showSessionBanner = playback.sessionExpired && playback.statusMessage != null
    val colors = LightThemeTokens.colors

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        if (showSessionBanner) {
            playback.statusMessage?.let { msg ->
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
        playback.error?.let { err ->
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
                            onSubmit = { query ->
                                overlayNav.navigate(OverlayDestination.SearchResults(query))
                            },
                        )
                        PhonoTab.Settings -> SettingsScreen(
                            vm = vm,
                            onLogout = { vm.logout() },
                        )
                    }
                }

                PhonoTabBar(
                    tabs = DefaultPhonoTabs,
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
                NavHost(
                    navController = overlayNavController,
                    startDestination = OverlayRoot,
                    modifier = if (showOverlayLayer) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier.size(0.dp)
                    },
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
                    sizeTransform = { null },
                ) {
                    composable(OverlayRoot) {}
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
