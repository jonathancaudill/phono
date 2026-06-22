package com.lightphone.spotify.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.DefaultEchoTabs
import com.lightphone.spotify.ui.components.EchoNavbar
import com.lightphone.spotify.ui.components.EchoTab
import com.lightphone.spotify.ui.screens.AlbumDetailScreen
import com.lightphone.spotify.ui.screens.AlbumsScreen
import com.lightphone.spotify.ui.screens.ArtistDetailScreen
import com.lightphone.spotify.ui.screens.LikedSongsScreen
import com.lightphone.spotify.ui.screens.LoginScreen
import com.lightphone.spotify.ui.screens.PlayingScreen
import com.lightphone.spotify.ui.screens.SearchResultsScreen
import com.lightphone.spotify.ui.screens.SearchScreen
import com.lightphone.spotify.ui.screens.SettingsScreen
import com.lightphone.spotify.ui.theme.EchoColors
import com.lightphone.spotify.ui.theme.EchoTheme
import java.net.URLDecoder

private val TabRoutes = setOf(
    EchoTab.Liked.route,
    EchoTab.Albums.route,
    EchoTab.Search.route,
    EchoTab.Settings.route,
)

@Composable
fun SpotifyApp(vm: AppViewModel = viewModel()) {
    val playback by vm.playback.collectAsState()
    EchoTheme {
        if (!playback.loggedIn) {
            LoginScreen(vm)
        } else {
            LaunchedEffect(Unit) { vm.onLoggedIn() }
            MainNavigation(vm)
        }
    }
}

@Composable
private fun MainNavigation(vm: AppViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route?.substringBefore('?')
    val playback by vm.playback.collectAsState()

    val currentTab = DefaultEchoTabs.firstOrNull { it.route == currentRoute } ?: EchoTab.Liked
    val showNavbar = currentRoute in TabRoutes

    Column(
        Modifier
            .fillMaxSize()
            .background(EchoColors.Background),
    ) {
        playback.statusMessage?.let { msg ->
            Text(
                msg,
                color = EchoColors.Warning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        playback.error?.let { err ->
            if (playback.statusMessage == null) {
                Text(
                    err,
                    color = EchoColors.Error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }

        Box(Modifier.weight(1f)) {
            NavHost(
                navController = navController,
                startDestination = EchoTab.Liked.route,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(EchoTab.Liked.route) {
                    LikedSongsScreen(
                        vm = vm,
                        onOpenPlaying = { navController.navigate(Routes.Playing) },
                        onPlayTrack = { index ->
                            vm.playLikedFrom(index)
                            navController.navigate(Routes.Playing)
                        },
                    )
                }
                composable(EchoTab.Albums.route) {
                    AlbumsScreen(
                        vm = vm,
                        onOpenPlaying = { navController.navigate(Routes.Playing) },
                        onOpenAlbum = { id, name ->
                            navController.navigate(Routes.album(id, name))
                        },
                    )
                }
                composable(EchoTab.Search.route) {
                    SearchScreen(
                        vm = vm,
                        onSubmit = { query ->
                            navController.navigate(Routes.searchResults(query))
                        },
                    )
                }
                composable(EchoTab.Settings.route) {
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
                            navController.navigate(Routes.album(albumId))
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
                            navController.navigate(Routes.album(id, name))
                        },
                        onOpenArtist = { id -> navController.navigate(Routes.artist(id)) },
                        onPlayTrack = { track ->
                            vm.playSearchTrack(track)
                            navController.navigate(Routes.Playing)
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
                        onOpenArtist = { id -> navController.navigate(Routes.artist(id)) },
                        onPlayTrack = { index ->
                            vm.playAlbumFrom(albumId, index)
                            navController.navigate(Routes.Playing)
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
                            navController.navigate(Routes.album(id, name))
                        },
                        onPlayTopTrack = { index ->
                            vm.playArtistTopTrack(index)
                            navController.navigate(Routes.Playing)
                        },
                    )
                }
            }
        }

        if (showNavbar) {
            EchoNavbar(
                tabs = DefaultEchoTabs,
                currentTab = currentTab,
                onTabSelected = { tab ->
                    navController.navigate(tab.route) {
                        popUpTo(EchoTab.Liked.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                statusMessage = if (!playback.networkOnline) "Device offline" else null,
            )
        }
    }
}
