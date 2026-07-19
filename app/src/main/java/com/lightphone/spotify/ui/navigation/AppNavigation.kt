package com.lightphone.spotify.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightphone.spotify.data.backend.BackendChoice
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.light.LightPhonoTheme
import com.lightphone.spotify.ui.screens.EmptyListMessage
import com.lightphone.spotify.ui.screens.LoginScreen
import com.lightphone.spotify.ui.screens.TidalLoginScreen
import com.lightphone.spotify.ui.screens.WebApiSetupScreen
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private data class AppAuthState(
    val authInitialized: Boolean,
    val loggedIn: Boolean,
    val webApiReady: Boolean,
)

@Composable
fun SpotifyApp(vm: AppViewModel = viewModel()) {
    val auth by remember(vm) {
        vm.playback.map { p ->
            AppAuthState(
                authInitialized = p.authInitialized,
                loggedIn = p.loggedIn,
                webApiReady = p.webApiReady,
            )
        }.distinctUntilChanged()
    }.collectAsState(
        initial = AppAuthState(
            authInitialized = vm.playback.value.authInitialized,
            loggedIn = vm.playback.value.loggedIn,
            webApiReady = vm.playback.value.webApiReady,
        ),
    )
    val libraryBootstrapping by vm.libraryBootstrapping.collectAsState()
    LightPhonoTheme {
        when {
            !auth.authInitialized -> Box(
                Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                EmptyListMessage("Loading…")
            }
            !auth.loggedIn ->
                if (vm.backendChoice == BackendChoice.TIDAL) TidalLoginScreen(vm) else LoginScreen(vm)
            // Spotify Step 2 only — TIDAL never uses the dev-app Web API.
            vm.backendChoice == BackendChoice.SPOTIFY && !auth.webApiReady -> WebApiSetupScreen(vm)
            else -> {
                LaunchedEffect(Unit) { vm.onLoggedIn() }
                if (libraryBootstrapping) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(LightThemeTokens.colors.background),
                    ) {
                        EmptyListMessage("Loading…")
                    }
                } else {
                    PhonoShell(vm)
                }
            }
        }
    }
}
