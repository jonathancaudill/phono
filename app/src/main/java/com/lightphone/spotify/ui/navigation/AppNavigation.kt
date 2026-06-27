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
import com.lightphone.spotify.ui.components.DefaultPhonoTabs
import com.lightphone.spotify.ui.components.PhonoNavbar
import com.lightphone.spotify.ui.components.PhonoTab
import com.lightphone.spotify.ui.components.StyledText
import com.lightphone.spotify.ui.theme.n
import com.lightphone.spotify.ui.screens.AlbumDetailScreen
import com.lightphone.spotify.ui.screens.AlbumsScreen
import com.lightphone.spotify.ui.screens.ArtistDetailScreen
import com.lightphone.spotify.ui.screens.LikedSongsScreen
import com.lightphone.spotify.ui.screens.LoginScreen
import com.lightphone.spotify.ui.screens.WebApiSetupScreen
import com.lightphone.spotify.ui.screens.PlayingScreen
import com.lightphone.spotify.ui.screens.QueueScreen
import com.lightphone.spotify.ui.screens.CreatePlaylistScreen
import com.lightphone.spotify.ui.screens.PlaylistDetailScreen
import com.lightphone.spotify.ui.screens.PlaylistPickerScreen
import com.lightphone.spotify.ui.screens.PlaylistsScreen
import com.lightphone.spotify.ui.screens.SearchResultsScreen
import com.lightphone.spotify.ui.screens.SearchScreen
import com.lightphone.spotify.ui.screens.SettingsScreen
import com.lightphone.spotify.ui.theme.PhonoColors
import com.lightphone.spotify.ui.theme.PhonoTheme
import java.net.URLDecoder

private const val OverlayRoot = "overlay_root"

@Composable
fun SpotifyApp(vm: AppViewModel = viewModel()) {
    val playback by vm.playback.collectAsState()
    PhonoTheme {
        when {
            !playback.authInitialized -> Box(
                Modifier
                    .fillMaxSize()
                    .background(PhonoColors.Background),
            )
            !playback.loggedIn -> LoginScreen(vm)
            !playback.webApiReady -> WebApiSetupScreen(vm)
            else -> {
                LaunchedEffect(Unit) { vm.onLoggedIn() }
                MainNavigation(vm)
            }
        }
    }
}

@Composable
private fun MainNavigation(vm: AppViewModel) {
    val tabNavController = rememberNavController()
    val overlayNavController = rememberNavController()
    val overlayNav = rememberOverlayNavigator(overlayNavController)
    val tabBackStackEntry by tabNavController.currentBackStackEntryAsState()
    val overlayBackStackEntry by overlayNavController.currentBackStackEntryAsState()
    val tabRoute = tabBackStackEntry?.destination?.route?.substringBefore('?')
    val overlayRoute = overlayBackStackEntry?.destination?.route?.substringBefore('?')
    val playback by vm.playback.collectAsState()

    val currentTab = DefaultPhonoTabs.firstOrNull { it.route == tabRoute } ?: PhonoTab.Liked
    val showOverlayLayer = overlayRoute != null && overlayRoute != OverlayRoot
    val showOfflineStrip = !playback.networkOnline

    Column(
        Modifier
            .fillMaxSize()
            .background(PhonoColors.Background),
    ) {
        playback.statusMessage?.let { msg ->
            StyledText(
                msg,
                size = 14,
                color = PhonoColors.Warning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = n(12), vertical = n(4)),
            )
        }
        playback.error?.let { err ->
            if (playback.statusMessage == null) {
                StyledText(
                    err,
                    size = 14,
                    color = PhonoColors.Error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = n(12), vertical = n(4)),
                )
            }
        }

        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize()) {
                NavHost(
                    navController = tabNavController,
                    startDestination = PhonoTab.Liked.route,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
                    sizeTransform = { null },
                ) {
                    composable(PhonoTab.Liked.route) {
                        LikedSongsScreen(
                            vm = vm,
                            onOpenPlaying = { overlayNav.navigate(Routes.Playing) },
                            onPlayTrack = { index ->
                                vm.playLikedFrom(index)
                                overlayNav.navigate(Routes.Playing)
                            },
                        )
                    }
                    composable(PhonoTab.Albums.route) {
                        AlbumsScreen(
                            vm = vm,
                            onOpenPlaying = { overlayNav.navigate(Routes.Playing) },
                            onOpenAlbum = { id, name ->
                                overlayNav.navigate(Routes.album(id, name))
                            },
                        )
                    }
                    composable(PhonoTab.Playlists.route) {
                        PlaylistsScreen(
                            vm = vm,
                            onOpenPlaying = { overlayNav.navigate(Routes.Playing) },
                            onOpenPlaylist = { id, name ->
                                overlayNav.navigate(Routes.playlist(id, name))
                            },
                            onCreatePlaylist = {
                                vm.resetCreatePlaylistState()
                                overlayNav.navigate(Routes.CreatePlaylist)
                            },
                        )
                    }
                    composable(PhonoTab.Search.route) {
                        SearchScreen(
                            vm = vm,
                            onSubmit = { query ->
                                overlayNav.navigate(Routes.searchResults(query))
                            },
                        )
                    }
                    composable(PhonoTab.Settings.route) {
                        SettingsScreen(
                            vm = vm,
                            onLogout = { vm.logout() },
                        )
                    }
                }

                PhonoNavbar(
                    tabs = DefaultPhonoTabs,
                    currentTab = currentTab,
                    onTabSelected = { tab ->
                        tabNavController.navigate(tab.route) {
                            popUpTo(PhonoTab.Liked.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    statusMessage = if (showOfflineStrip) "Device offline" else null,
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

            ContextMenuHost(
                vm = vm,
                onNavigateToPlaylistPicker = { uri ->
                    overlayNav.navigate(Routes.playlistPicker(uri))
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
                overlayNav.navigate(Routes.album(albumId))
            },
            onOpenQueue = { overlayNav.navigate(Routes.Queue) },
            onAddToPlaylist = { uri ->
                overlayNav.navigate(Routes.playlistPicker(uri))
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
        val query = URLDecoder.decode(entry.arguments?.getString("query").orEmpty(), Charsets.UTF_8.name())
        SearchResultsScreen(
            vm = vm,
            query = query,
            onBack = { overlayNavController.popBackStack() },
            onOpenAlbum = { id, name ->
                overlayNav.navigate(Routes.album(id, name))
            },
            onOpenArtist = { id -> overlayNav.navigate(Routes.artist(id)) },
            onPlayTrack = { track ->
                vm.playSearchTrack(track)
                overlayNav.navigate(Routes.Playing)
            },
            onOpenPlaylist = { id, name ->
                overlayNav.navigate(Routes.playlist(id, name))
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
        val title = URLDecoder.decode(
            entry.arguments?.getString("title").orEmpty(),
            Charsets.UTF_8.name(),
        )
        AlbumDetailScreen(
            vm = vm,
            albumId = albumId,
            fallbackTitle = title.ifBlank { "Album" },
            onBack = { overlayNavController.popBackStack() },
            onOpenArtist = { id -> overlayNav.navigate(Routes.artist(id)) },
            onPlayTrack = { index ->
                vm.playAlbumFrom(albumId, index)
                overlayNav.navigate(Routes.Playing)
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
        val title = URLDecoder.decode(
            entry.arguments?.getString("title").orEmpty(),
            Charsets.UTF_8.name(),
        )
        PlaylistDetailScreen(
            vm = vm,
            playlistId = playlistId,
            fallbackTitle = title.ifBlank { "Playlist" },
            onBack = { overlayNavController.popBackStack() },
            onPlayTrack = { index ->
                vm.playPlaylistFrom(playlistId, index)
                overlayNav.navigate(Routes.Playing)
            },
        )
    }
    composable(Routes.CreatePlaylist) {
        CreatePlaylistScreen(
            vm = vm,
            onBack = { overlayNavController.popBackStack() },
            onCreated = { id, name ->
                overlayNavController.popBackStack()
                overlayNav.navigate(Routes.playlist(id, name))
            },
        )
    }
    composable(
        route = Routes.PlaylistPicker,
        arguments = listOf(navArgument("trackUri") { type = NavType.StringType }),
    ) { entry ->
        val trackUri = URLDecoder.decode(
            entry.arguments?.getString("trackUri").orEmpty(),
            Charsets.UTF_8.name(),
        )
        PlaylistPickerScreen(
            vm = vm,
            trackUri = trackUri,
            onBack = { overlayNavController.popBackStack() },
            onCreatePlaylist = {
                vm.resetCreatePlaylistState()
                overlayNav.navigate(Routes.CreatePlaylist)
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
                overlayNav.navigate(Routes.album(id, name))
            },
            onPlayTopTrack = { index ->
                vm.playArtistTopTrack(index)
                overlayNav.navigate(Routes.Playing)
            },
        )
    }
}
