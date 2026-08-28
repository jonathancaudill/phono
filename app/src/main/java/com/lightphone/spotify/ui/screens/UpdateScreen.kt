package com.lightphone.spotify.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.consumeScrimTouches
import com.lightphone.spotify.update.UpdateUiState
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * Full-screen update prompt in the LightOS modal shape: centred message, actions
 * pinned to the bottom bar corners.
 */
@Composable
fun UpdateScreen(
    state: UpdateUiState,
    onIgnore: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state is UpdateUiState.Idle) return
    val busy = state is UpdateUiState.Checking ||
        state is UpdateUiState.Downloading ||
        state is UpdateUiState.Installing

    BackHandler(enabled = true) {
        if (!busy) onDismiss()
    }

    val message = when (state) {
        is UpdateUiState.Available -> "A new update is available"
        is UpdateUiState.Checking -> "Checking…"
        is UpdateUiState.Downloading -> state.percent
            ?.let { "Downloading… $it%" }
            ?: "Downloading…"
        is UpdateUiState.Installing -> "Installing…"
        is UpdateUiState.UpToDate -> "No new updates"
        is UpdateUiState.Failed -> state.message
        is UpdateUiState.Idle -> ""
    }
    val detail = (state as? UpdateUiState.Available)?.let { "phono ${it.version}" }

    val actions: List<LightBarButton?> = when (state) {
        is UpdateUiState.Available -> listOf(
            LightBarButton.Text(text = "IGNORE", onClick = onIgnore),
            LightBarButton.Text(text = "APPLY", onClick = onApply),
        )
        is UpdateUiState.UpToDate, is UpdateUiState.Failed -> listOf(
            LightBarButton.Text(text = "CLOSE", onClick = onDismiss),
        )
        else -> emptyList()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background)
            .consumeScrimTouches(),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LightText(
                    text = message,
                    variant = LightTextVariant.Copy,
                    color = if (state is UpdateUiState.Failed) PhonoSemanticColors.Error else null,
                    align = TextAlign.Center,
                )
                if (detail != null) {
                    LightText(
                        text = detail,
                        variant = LightTextVariant.Detail,
                        color = PhonoSemanticColors.Placeholder,
                        align = TextAlign.Center,
                        modifier = Modifier.padding(top = legacyNToGridDp(8)),
                    )
                }
            }
        }

        LightBottomBar(items = actions)
    }
}
