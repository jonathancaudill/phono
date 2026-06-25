package com.lightphone.spotify.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.DefaultMonoTabs
import com.lightphone.spotify.ui.components.MonoNavbar
import com.lightphone.spotify.ui.components.MonoTab
import com.lightphone.spotify.ui.components.StyledText
import com.lightphone.spotify.ui.theme.n
import com.lightphone.spotify.ui.screens.AlbumDetailScreen
import com.lightphone.spotify.ui.screens.AlbumsScreen
import com.lightphone.spotify.ui.screens.ArtistDetailScreen
import com.lightphone.spotify.ui.screens.LikedSongsScreen
import com.lightphone.spotify.ui.screens.LoginScreen
import com.lightphone.spotify.ui.screens.WebApiSetupScreen
import com.lightphone.spotify.ui.screens.PlayingScreen
import com.lightphone.spotify.ui.screens.SearchResultsScreen
import com.lightphone.spotify.ui.screens.SearchScreen
import com.lightphone.spotify.ui.screens.SettingsScreen
import com.lightphone.spotify.ui.theme.MonoColors
import com.lightphone.spotify.ui.theme.MonoTheme
import java.net.URLDecoder

private val TabRoutes = setOf(
    MonoTab.Liked.route,
    MonoTab.Albums.route,
    MonoTab.Search.route,
    MonoTab.Settings.route,
)

@Composable
fun SpotifyApp(vm: AppViewModel = viewModel()) {
    val playback by vm.playback.collectAsState()
    MonoTheme {
        when {
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
    val navController = rememberNavController()
    val overlayNav = rememberOverlayNavigator(navController)
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route?.substringBefore('?')
    val playback by vm.playback.collectAsState()

    val currentTab = DefaultMonoTabs.firstOrNull { it.route == currentRoute } ?: MonoTab.Liked
    val showNavbar = currentRoute in TabRoutes

    Column(
        Modifier
            .fillMaxSize()
            .background(MonoColors.Background),
    ) {
        playback.statusMessage?.let { msg ->
            StyledText(
                msg,
                size = 14,
                color = MonoColors.Warning,
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
                    color = MonoColors.Error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = n(12), vertical = n(4)),
                )
            }
        }

        Box(Modifier.weight(1f)) {
            NavHost(
                navController = navController,
                startDestination = MonoTab.Liked.route,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(MonoTab.Liked.route) {
                    LikedSongsScreen(
                        vm = vm,
                        onOpenPlaying = { overlayNav.navigate(Routes.Playing) },
                        onPlayTrack = { index ->
                            vm.playLikedFrom(index)
                            overlayNav.navigate(Routes.Playing)
                        },
                    )
                }
                composable(MonoTab.Albums.route) {
                    AlbumsScreen(
                        vm = vm,
                        onOpenPlaying = { overlayNav.navigate(Routes.Playing) },
                        onOpenAlbum = { id, name ->
                            overlayNav.navigate(Routes.album(id, name))
                        },
                    )
                }
                composable(MonoTab.Search.route) {
                    SearchScreen(
                        vm = vm,
                        onSubmit = { query ->
                            overlayNav.navigate(Routes.searchResults(query))
                        },
                    )
                }
                composable(MonoTab.Settings.route) {
                    SettingsScreen(
                        vm = vm,
                        onLogout = { vm.logout() },
                    )
                }
                composable(Routes.Playing) {
                    PlayingScreen(
                        vm = vm,
                        onBack = { navController.popBackStack() },
                        onOpenAlbum = { albumId ->
                            overlayNav.navigate(Routes.album(albumId))
                        },
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
                        onBack = { navController.popBackStack() },
                        onOpenAlbum = { id, name ->
                            overlayNav.navigate(Routes.album(id, name))
                        },
                        onOpenArtist = { id -> overlayNav.navigate(Routes.artist(id)) },
                        onPlayTrack = { track ->
                            vm.playSearchTrack(track)
                            overlayNav.navigate(Routes.Playing)
                        },
                        onPlayPlaylist = { playlistId ->
                            vm.playSearchPlaylist(playlistId) {
                                overlayNav.navigate(Routes.Playing)
                            }
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
                        onBack = { navController.popBackStack() },
                        onOpenArtist = { id -> overlayNav.navigate(Routes.artist(id)) },
                        onPlayTrack = { index ->
                            vm.playAlbumFrom(albumId, index)
                            overlayNav.navigate(Routes.Playing)
                        },
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
                        onBack = { navController.popBackStack() },
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
        }

        if (showNavbar) {
            MonoNavbar(
                tabs = DefaultMonoTabs,
                currentTab = currentTab,
                onTabSelected = { tab ->
                    navController.navigate(tab.route) {
                        popUpTo(MonoTab.Liked.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                statusMessage = if (!playback.networkOnline) "Device offline" else null,
            )
        }
    }
}
