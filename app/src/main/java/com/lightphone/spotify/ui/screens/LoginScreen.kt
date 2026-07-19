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
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens

private const val REDIRECT_PREFIX = "http://127.0.0.1:8898/login"

/**
 * Exact scheme+host+port+path match against [redirect] — NOT `toString().startsWith(...)`,
 * which would also accept `$redirect-evil-suffix` since that string literally starts with
 * the expected prefix.
 */
private fun matchesRedirectUri(url: Uri, redirect: String): Boolean {
    val expected = Uri.parse(redirect)
    return url.scheme == expected.scheme &&
        url.host == expected.host &&
        url.port == expected.port &&
        url.path == expected.path
}

@Composable
fun LoginScreen(vm: AppViewModel) {
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
                LightText(text = "Step 1: Playback", variant = LightTextVariant.Copy)
                LightText(
                    text = "Sign in with Spotify for audio playback (librespot).",
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
                                    if (matchesRedirectUri(uri, REDIRECT_PREFIX)) {
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
