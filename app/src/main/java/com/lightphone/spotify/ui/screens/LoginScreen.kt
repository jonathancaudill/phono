package com.lightphone.spotify.ui.screens

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.theme.EchoColors

private const val REDIRECT_PREFIX = "http://127.0.0.1:8898/login"

@Composable
fun LoginScreen(vm: AppViewModel) {
    val playback by vm.playback.collectAsState()
    val authUrl = remember { vm.beginLogin() }
    Box(Modifier.fillMaxSize().background(EchoColors.Background)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
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
        playback.error?.let { message ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(EchoColors.Background.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text(
                        text = "Login failed",
                        color = EchoColors.Foreground,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = message,
                        color = EchoColors.Error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}
