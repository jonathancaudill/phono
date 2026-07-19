package com.lightphone.spotify.ui.screens

import android.net.Uri
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.lightphone.spotify.data.tidal.TidalAuth
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * TIDAL PKCE login. Reuses the same WebView-intercept pattern as [LoginScreen]:
 * the auth URL comes from the backend ([AppViewModel.beginLogin] ->
 * `TidalPlaybackBackend.beginLogin` -> `TidalAuth.buildAuthorizeUrl`), and the
 * intercepted `?code=` is exchanged via [AppViewModel.completeLogin].
 *
 * Single-auth: there is no Step 2 for TIDAL.
 */
@Composable
fun TidalLoginScreen(vm: AppViewModel) {
    val playback by vm.playback.collectAsState()
    var authUrl by remember { mutableStateOf<String?>(null) }
    val colors = LightThemeTokens.colors
    LaunchedEffect(Unit) {
        authUrl = vm.beginLogin()
    }
    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = legacyNToGridDp(22), vertical = legacyNToGridDp(8)),
            ) {
                LightText(text = "Sign in to TIDAL", variant = LightTextVariant.Copy)
                LightText(
                    text = "Sign in for lossless / hi-res playback and your library.",
                    variant = LightTextVariant.Detail,
                    color = PhonoSemanticColors.Placeholder,
                )
            }
            if (authUrl != null) {
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
                                    val uri = request?.url ?: return false
                                    if (matchesRedirectUri(uri, TidalAuth.REDIRECT_URI)) {
                                        val code = uri.getQueryParameter("code")
                                        val state = uri.getQueryParameter("state")
                                        if (code != null) vm.completeLogin(code, state)
                                        return true
                                    }
                                    return false
                                }
                            }
                            loadUrl(authUrl!!)
                        }
                    },
                )
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
                }
            }
        }
    }
}
