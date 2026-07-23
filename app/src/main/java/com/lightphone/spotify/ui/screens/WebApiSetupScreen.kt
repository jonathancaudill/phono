package com.lightphone.spotify.ui.screens

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.lightphone.spotify.data.webapi.parseWebApiQrPayload
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.configureOAuthWebView
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.lightphone.spotify.ui.phono.PhonoTextButton
import com.lightphone.spotify.ui.PhonoQrScanner
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens

@Composable
fun WebApiSetupScreen(vm: AppViewModel) {
    val playback by vm.playback.collectAsState()
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var showWebView by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var authUrl by remember { mutableStateOf<String?>(null) }
    var qrScanMessage by remember { mutableStateOf<String?>(null) }
    var qrScanIsError by remember { mutableStateOf(false) }
    var pendingScan by remember { mutableStateOf<String?>(null) }
    val colors = LightThemeTokens.colors

    val sessionState = playback.webApiSessionState
    val credentialsConfigured = sessionState !is WebApiSessionState.NotConfigured

    LaunchedEffect(pendingScan) {
        val raw = pendingScan ?: return@LaunchedEffect
        pendingScan = null
        parseWebApiQrPayload(raw)
            .onSuccess { payload ->
                clientId = payload.clientId
                clientSecret = payload.clientSecret
                // A QR code only proves it was scannable, not that it's really the
                // user's own dev app — show the Client ID prominently so the user has
                // a chance to notice a substituted one before authorizing against it.
                qrScanMessage = "Scanned Client ID: ${payload.clientId}\n" +
                    "Confirm this is your app, then tap Connect Web API."
                qrScanIsError = false
            }
            .onFailure { error ->
                qrScanMessage = error.message ?: "Invalid QR code."
                qrScanIsError = true
            }
        showQrScanner = false
    }

    if (showQrScanner) {
        PhonoQrScanner(
            title = "Scan QR Code",
            onScanned = { pendingScan = it },
            onBack = { showQrScanner = false },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    if (showWebView && authUrl != null) {
        Box(Modifier.fillMaxSize().background(colors.background).imePadding()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        configureOAuthWebView(WebApiAuth.REDIRECT_URI) { code, state ->
                            vm.completeWebApiAuth(code, state) { result ->
                                if (result.isSuccess) showWebView = false
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

        Column(Modifier.fillMaxSize()) {
            LightText(
                text = "Enter your Spotify Developer app credentials, or scan the QR code. Visit www.jonathancaudill.github.io/phono for more info.",
                variant = LightTextVariant.Detail,
                color = PhonoSemanticColors.Placeholder,
            )
            if (!credentialsConfigured) {
                UnderlinedField(
                    value = clientId,
                    onChange = {
                        clientId = it
                        qrScanMessage = null
                        qrScanIsError = false
                    },
                    placeholder = "Client ID",
                )
                UnderlinedField(
                    value = clientSecret,
                    onChange = {
                        clientSecret = it
                        qrScanMessage = null
                        qrScanIsError = false
                    },
                    placeholder = "Client Secret",
                    password = true,
                )
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PhonoTextButton(
                        text = "SCAN QR",
                        onClick = {
                            qrScanMessage = null
                            qrScanIsError = false
                            showQrScanner = true
                        },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            qrScanMessage?.let { message ->
                LightText(
                    text = message,
                    variant = LightTextVariant.Detail,
                    color = if (qrScanIsError) PhonoSemanticColors.Error else PhonoSemanticColors.Placeholder,
                )
            }
            playback.error?.let { message ->
                LightText(text = message, variant = LightTextVariant.Detail, color = PhonoSemanticColors.Error)
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PhonoTextButton(
                    text = "CONNECT WEB API",
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
            }
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
