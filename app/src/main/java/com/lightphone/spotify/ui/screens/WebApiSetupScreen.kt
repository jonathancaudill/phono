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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.viewinterop.AndroidView
import com.lightphone.spotify.data.webapi.WebApiAuth
import com.lightphone.spotify.data.webapi.WebApiSessionState
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.lightphone.spotify.ui.phono.PhonoTextButton
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens

@Composable
fun WebApiSetupScreen(vm: AppViewModel) {
    val playback by vm.playback.collectAsState()
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var showWebView by remember { mutableStateOf(false) }
    var authUrl by remember { mutableStateOf<String?>(null) }
    val colors = LightThemeTokens.colors

    val sessionState = playback.webApiSessionState
    val credentialsConfigured = sessionState !is WebApiSessionState.NotConfigured

    if (showWebView && authUrl != null) {
        Box(Modifier.fillMaxSize().background(colors.background)) {
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
                        .background(colors.background.copy(alpha = 0.92f)),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(
                        text = message,
                        variant = LightTextVariant.Detail,
                        color = PhonoSemanticColors.Error,
                        modifier = Modifier.padding(legacyNToGridDp(24)),
                    )
                }
            }
        }
        return
    }

    PhonoScreenShell(
        title = "Step 2: Web API",
        hideBackButton = true,
        rightIconVisible = false,
        horizontalPadding = legacyNToGridDp(22),
        contentGap = legacyNToGridDp(16),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (sessionState is WebApiSessionState.Expired) {
            LightText(
                text = "Your Web API session expired. Reconnect to browse your library — playback is unaffected.",
                variant = LightTextVariant.Detail,
                color = PhonoSemanticColors.Placeholder,
            )
            Spacer(Modifier.height(legacyNToGridDp(4)))
            PhonoTextButton(
                text = "Reconnect Web API",
                onClick = {
                    authUrl = vm.buildWebApiAuthorizeUrl()
                    showWebView = true
                },
            )
            playback.error?.let { message ->
                LightText(text = message, variant = LightTextVariant.Detail, color = PhonoSemanticColors.Error)
            }
            return@PhonoScreenShell
        }

        LightText(
            text = "Enter your Spotify Developer app credentials. Create one at developer.spotify.com/dashboard.",
            variant = LightTextVariant.Detail,
            color = PhonoSemanticColors.Placeholder,
        )
        if (!credentialsConfigured) {
        LightText(
            text = "Redirect URI (copy exactly into dashboard):\n${WebApiAuth.REDIRECT_URI}\nPackage: com.lightphone.spotify",
            variant = LightTextVariant.Detail,
            color = PhonoSemanticColors.Placeholder,
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
        Spacer(Modifier.height(legacyNToGridDp(4)))
        }
        PhonoTextButton(
            text = "Connect Web API",
            onClick = {
                if (credentialsConfigured) {
                    authUrl = vm.buildWebApiAuthorizeUrl()
                    showWebView = true
                } else if (clientId.isNotBlank() && clientSecret.isNotBlank()) {
                    vm.saveWebApiCredentials(clientId.trim(), clientSecret.trim())
                    authUrl = vm.buildWebApiAuthorizeUrl()
                    showWebView = true
                }
            },
        )
        playback.error?.let { message ->
            LightText(text = message, variant = LightTextVariant.Detail, color = PhonoSemanticColors.Error)
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
    val colors = LightThemeTokens.colors
    val typography = LightThemeTokens.typography
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(bottom = legacyNToGridDp(6)),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = typography.copy.copy(color = colors.content),
                cursorBrush = SolidColor(colors.content),
                singleLine = true,
                visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        LightText(
                            text = placeholder,
                            variant = LightTextVariant.Copy,
                            color = PhonoSemanticColors.Placeholder,
                        )
                    }
                    inner()
                },
            )
        }
        Box(Modifier.fillMaxWidth().height(legacyNToGridDp(1)).background(colors.content))
    }
}
