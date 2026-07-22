package com.lightphone.spotify.ui.screens

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.SpotifyOAuthLoopback
import com.lightphone.spotify.ui.WebViewAuthCleanup
import com.lightphone.spotify.ui.configureOAuthWebView
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

private const val REDIRECT_URI = "http://127.0.0.1:8898/login"

@Composable
fun LoginScreen(vm: AppViewModel) {
    val playback by vm.playback.collectAsState()
    var authUrl by remember { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(true) }
    var signingIn by remember { mutableStateOf(false) }
    var codeConsumed by remember { mutableStateOf(false) }
    var retryKey by remember { mutableStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val colors = LightThemeTokens.colors

    LaunchedEffect(retryKey) {
        preparing = true
        signingIn = false
        codeConsumed = false
        authUrl = null
        WebViewAuthCleanup.clear()
        authUrl = vm.beginLogin()
        preparing = false
        webView?.loadUrl(authUrl!!)
    }

    DisposableEffect(retryKey) {
        fun deliverCode(code: String, state: String?, via: String) {
            android.util.Log.e("LoginScreen", "oauth code via=$via")
            if (codeConsumed || signingIn) return
            codeConsumed = true
            signingIn = true
            vm.completeLogin(code, state)
        }
        SpotifyOAuthLoopback.start { code, state -> deliverCode(code, state, "loopback") }
        onDispose { SpotifyOAuthLoopback.stop() }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.background).imePadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = legacyNToGridDp(22), vertical = legacyNToGridDp(8)),
            ) {
                LightText(text = "Step 1: Playback", variant = LightTextVariant.Copy)
                LightText(
                    text = "Sign in with Spotify for audio playback (librespot).",
                    variant = LightTextVariant.Detail,
                    color = PhonoSemanticColors.Placeholder,
                )
            }
            when {
                preparing -> Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(text = "Preparing sign-in…", variant = LightTextVariant.Detail)
                }
                authUrl != null -> AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { context ->
                        WebView(context).apply {
                            webView = this
                            configureOAuthWebView(REDIRECT_URI) { code, state ->
                                if (codeConsumed || signingIn) return@configureOAuthWebView
                                codeConsumed = true
                                signingIn = true
                                android.util.Log.e("LoginScreen", "oauth code via=webview")
                                vm.completeLogin(code, state)
                            }
                            loadUrl(authUrl!!)
                        }
                    },
                    update = { view ->
                        webView = view
                    },
                )
            }
        }
        if (signingIn && playback.error == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center,
            ) {
                LightText(text = "Signing in…", variant = LightTextVariant.Copy)
            }
        }
        playback.error?.let { message ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(legacyNToGridDp(24)),
                ) {
                    LightText(text = "Login failed", variant = LightTextVariant.Copy)
                    LightText(
                        text = message,
                        variant = LightTextVariant.Detail,
                        color = PhonoSemanticColors.Error,
                        modifier = Modifier.padding(top = legacyNToGridDp(12)),
                    )
                    LightText(
                        text = "Try again",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier
                            .padding(top = legacyNToGridDp(20))
                            .lightClickable {
                                vm.clearLoginError()
                                retryKey += 1
                            },
                    )
                }
            }
        }
    }
}
