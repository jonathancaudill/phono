package com.lightphone.spotify.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.lightphone.spotify.ui.ContextMenuAction
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoHeaderIcon
import com.lightphone.spotify.ui.phono.consumeScrimTouches
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.delay

private val ContextMenuHorizontalInset @Composable get() = legacyNToGridDp(37)
private val ContextMenuTopInset @Composable get() = legacyNToGridDp(14)
private val OverlayBottomInset @Composable get() = legacyNToGridDp(30)

data class PhonoContextMenuItem(
    val label: String,
    val action: ContextMenuAction,
)

@Composable
fun PhonoContextMenuOverlay(
    items: List<PhonoContextMenuItem>,
    onItemClick: (PhonoContextMenuItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LightThemeTokens.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .lightClickable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = ContextMenuHorizontalInset, top = ContextMenuTopInset)
                .lightClickable(onClick = {}),
        ) {
            items.forEach { item ->
                LightText(
                    text = item.label,
                    variant = LightTextVariant.Button,
                    modifier = Modifier.lightClickable { onItemClick(item) },
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = OverlayBottomInset)
                .lightClickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            PhonoHeaderIcon(
                icon = Icons.Default.Close,
                onClick = onDismiss,
                modifier = Modifier.size(legacyNToGridDp(32)),
                contentDescription = "Dismiss",
            )
        }
    }
}

@Composable
fun PhonoCopiedOverlay(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    autoDismissMs: Long = 1750L,
) {
    val colors = LightThemeTokens.colors
    LaunchedEffect(Unit) {
        delay(autoDismissMs)
        onDismiss()
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .lightClickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = "copied",
            variant = LightTextVariant.Button,
            align = TextAlign.Center,
        )
    }
}

@Composable
fun PhonoDeleteConfirmOverlay(
    message: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    confirmText: String = "Confirm",
) {
    val colors = LightThemeTokens.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .consumeScrimTouches()
            .lightClickable(onClick = onCancel),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = legacyNToGridDp(22), top = legacyNToGridDp(5))
                .size(legacyNToGridDp(32))
                .lightClickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            PhonoHeaderIcon(
                icon = Icons.AutoMirrored.Filled.ArrowBackIos,
                onClick = onCancel,
                modifier = Modifier.size(legacyNToGridDp(28)),
                contentDescription = "Cancel",
            )
        }
        LightText(
            text = message,
            variant = LightTextVariant.Paragraph,
            align = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = ContextMenuHorizontalInset),
        )
        LightText(
            text = confirmText,
            variant = LightTextVariant.Subtitle,
            align = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .lightClickable(onClick = onConfirm)
                .padding(bottom = OverlayBottomInset, top = legacyNToGridDp(15), start = legacyNToGridDp(30), end = legacyNToGridDp(30)),
        )
    }
}
