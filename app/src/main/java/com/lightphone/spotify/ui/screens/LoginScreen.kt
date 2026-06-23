package com.lightphone.spotify.ui.screens

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.StyledText
import com.lightphone.spotify.ui.theme.MonoColors
import com.lightphone.spotify.ui.theme.n

private const val REDIRECT_PREFIX = "http://127.0.0.1:8898/login"

@Composable
fun LoginScreen(vm: AppViewModel) {
    val playback by vm.playback.collectAsState()
    val authUrl = remember { vm.beginLogin() }
    Box(Modifier.fillMaxSize().background(MonoColors.Background)) {
        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = n(22), vertical = n(8)),
            ) {
                StyledText("Step 1: Playback", size = 22, color = MonoColors.Foreground)
                StyledText(
                    "Sign in with Spotify for audio playback (librespot).",
                    size = 16,
                    color = MonoColors.Placeholder,
                )
            }
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                                "Chrome/126.0.0.0 Mobile Safari/537.36"
                        val cookieManager = android.webkit.CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val url = request?.url?.toString() ?: return false
                                if (url.startsWith(REDIRECT_PREFIX)) {
                                    val code = request.url.getQueryParameter("code")
                                    if (code != null) vm.completeLogin(code)
                                    return true
                                }
                                return false
                            }
                        }
                        loadUrl(authUrl)
                    }
                },
            )
        }
        playback.error?.let { message ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MonoColors.Background.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(n(24)),
                ) {
                    StyledText("Login failed", size = 22, color = MonoColors.Foreground)
                    StyledText(
                        message,
                        size = 16,
                        color = MonoColors.Error,
                        modifier = Modifier.padding(top = n(12)),
                    )
                }
            }
        }
    }
}
