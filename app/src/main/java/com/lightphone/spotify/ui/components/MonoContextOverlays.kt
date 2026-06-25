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
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.lightphone.spotify.ui.ContextMenuAction
import com.lightphone.spotify.ui.theme.MonoColors
import com.lightphone.spotify.ui.theme.n
import kotlinx.coroutines.delay

private val ContextMenuHorizontalInset = n(37)
private val ContextMenuTopInset = n(14)
private val OverlayBottomInset = n(30)
// size 26 text + slightly relaxed gap between lines
private const val ContextMenuItemFontSize = 26
private const val ContextMenuItemLineHeight = 50

data class MonoContextMenuItem(
    val label: String,
    val action: ContextMenuAction,
)

@Composable
fun MonoContextMenuOverlay(
    items: List<MonoContextMenuItem>,
    onItemClick: (MonoContextMenuItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MonoColors.Background)
            .tap(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = ContextMenuHorizontalInset, top = ContextMenuTopInset)
                .tap(onClick = {}),
        ) {
            items.forEach { item ->
                StyledText(
                    text = item.label,
                    size = ContextMenuItemFontSize,
                    lineHeight = ContextMenuItemLineHeight,
                    color = MonoColors.Foreground,
                    modifier = Modifier.tap { onItemClick(item) },
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = OverlayBottomInset)
                .tap(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Dismiss",
                tint = MonoColors.Foreground,
                modifier = Modifier.size(n(32)),
            )
        }
    }
}

@Composable
fun MonoCopiedOverlay(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    autoDismissMs: Long = 1750L,
) {
    LaunchedEffect(Unit) {
        delay(autoDismissMs)
        onDismiss()
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MonoColors.Background)
            .tap(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        StyledText(
            text = "copied",
            size = 30,
            color = MonoColors.Foreground,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun MonoDeleteConfirmOverlay(
    message: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    confirmText: String = "Confirm",
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MonoColors.Background),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = n(22), top = n(5))
                .size(n(32))
                .tap(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBackIos,
                contentDescription = "Cancel",
                tint = MonoColors.Foreground,
                modifier = Modifier.size(n(28)),
            )
        }
        StyledText(
            text = message,
            size = 18,
            color = MonoColors.Foreground,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = ContextMenuHorizontalInset),
        )
        StyledText(
            text = confirmText,
            size = 40,
            color = MonoColors.Foreground,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .tap(onClick = onConfirm)
                .padding(bottom = OverlayBottomInset, top = n(15), start = n(30), end = n(30)),
        )
    }
}
