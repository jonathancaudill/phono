package com.lightphone.spotify.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.light.LightPhonoTheme
import com.lightphone.spotify.ui.screens.EmptyListMessage
import com.lightphone.spotify.ui.screens.LoginScreen
import com.lightphone.spotify.ui.screens.WebApiSetupScreen
import com.thelightphone.sdk.ui.LightThemeTokens

@Composable
fun SpotifyApp(vm: AppViewModel = viewModel()) {
    val playback by vm.playback.collectAsState()
    val libraryBootstrapping by vm.libraryBootstrapping.collectAsState()
    LightPhonoTheme {
        when {
            !playback.authInitialized -> Box(
                Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                EmptyListMessage("Loading…")
            }
            !playback.loggedIn -> LoginScreen(vm)
            !playback.webApiReady -> WebApiSetupScreen(vm)
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
