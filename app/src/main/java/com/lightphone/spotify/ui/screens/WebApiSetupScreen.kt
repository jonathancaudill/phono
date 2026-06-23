package com.lightphone.spotify.ui.screens

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.viewinterop.AndroidView
import com.lightphone.spotify.data.webapi.WebApiAuth
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.MonoContentContainer
import com.lightphone.spotify.ui.components.MonoStyledButton
import com.lightphone.spotify.ui.components.StyledText
import com.lightphone.spotify.ui.theme.MonoColors
import com.lightphone.spotify.ui.theme.PublicSans
import com.lightphone.spotify.ui.theme.n
import com.lightphone.spotify.ui.theme.nSp

@Composable
fun WebApiSetupScreen(vm: AppViewModel) {
    val playback by vm.playback.collectAsState()
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var showWebView by remember { mutableStateOf(false) }
    var authUrl by remember { mutableStateOf<String?>(null) }

    if (showWebView && authUrl != null) {
        Box(Modifier.fillMaxSize().background(MonoColors.Background)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val url = request?.url?.toString() ?: return false
                                if (url.startsWith(WebApiAuth.REDIRECT_URI)) {
                                    val code = request.url.getQueryParameter("code")
                                    if (code != null) {
                                        vm.completeWebApiAuth(code) { result ->
                                            if (result.isSuccess) showWebView = false
                                        }
                                    }
                                    return true
                                }
                                return false
                            }
                        }
                        loadUrl(authUrl!!)
                    }
                },
            )
            playback.error?.let { message ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MonoColors.Background.copy(alpha = 0.92f)),
                    contentAlignment = Alignment.Center,
                ) {
                    StyledText(message, size = 16, color = MonoColors.Error, modifier = Modifier.padding(n(24)))
                }
            }
        }
        return
    }

    MonoContentContainer(
        title = "Step 2: Web API",
        hideBackButton = true,
        rightIconVisible = false,
        horizontalPadding = n(22),
        contentGap = n(16),
        modifier = Modifier.fillMaxSize(),
    ) {
        StyledText(
            "Enter your Spotify Developer app credentials. Create one at developer.spotify.com/dashboard.",
            size = 16,
            color = MonoColors.Placeholder,
        )
        StyledText(
            "Redirect URI (copy exactly into dashboard):\n${WebApiAuth.REDIRECT_URI}\nPackage: com.lightphone.spotify",
            size = 14,
            color = MonoColors.Placeholder,
        )
        UnderlinedField(
            value = clientId,
            onChange = { clientId = it },
            placeholder = "Client ID",
        )
        UnderlinedField(
            value = clientSecret,
            onChange = { clientSecret = it },
            placeholder = "Client Secret",
            password = true,
        )
        Spacer(Modifier.height(n(4)))
        MonoStyledButton(
            text = "Connect Web API",
            onClick = {
                if (clientId.isNotBlank() && clientSecret.isNotBlank()) {
                    vm.saveWebApiCredentials(clientId.trim(), clientSecret.trim())
                    authUrl = vm.buildWebApiAuthorizeUrl()
                    showWebView = true
                }
            },
        )
        playback.error?.let { message ->
            StyledText(message, size = 16, color = MonoColors.Error)
        }
    }
}

@Composable
private fun UnderlinedField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    password: Boolean = false,
) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(bottom = n(6)),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = TextStyle(color = MonoColors.Foreground, fontSize = nSp(24), fontFamily = PublicSans),
                cursorBrush = SolidColor(MonoColors.Foreground),
                singleLine = true,
                visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        StyledText(placeholder, size = 24, color = MonoColors.Placeholder)
                    }
                    inner()
                },
            )
        }
        Box(Modifier.fillMaxWidth().height(n(1)).background(MonoColors.Foreground))
    }
}
