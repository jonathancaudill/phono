package com.lightphone.spotify.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.lightphone.spotify.ui.theme.MonoColors
import com.lightphone.spotify.ui.theme.n

/**
 * Square (or sized-by-[modifier]) artwork with the template's grey placeholder
 * (#282828) when there's no URL or the load fails.
 */
@Composable
fun MonoFallbackImage(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    placeholderIcon: ImageVector = Icons.Default.MusicNote,
    placeholderIconSize: Dp = n(100),
    placeholderText: String? = null,
    disabled: Boolean = false,
    contentDescription: String? = null,
) {
    var failed by remember(imageUrl) { mutableStateOf(false) }
    val iconTint = if (disabled) MonoColors.DisabledIcon else MonoColors.Foreground

    if (imageUrl.isNullOrBlank() || failed) {
        Box(
            modifier = modifier.background(MonoColors.PlaceholderBg),
            contentAlignment = Alignment.Center,
        ) {
            if (placeholderText != null) {
                StyledText(
                    placeholderText,
                    size = 24,
                    color = iconTint,
                    textAlign = TextAlign.Center,
                )
            } else {
                Icon(
                    placeholderIcon,
                    contentDescription = contentDescription,
                    tint = iconTint,
                    modifier = Modifier.size(placeholderIconSize),
                )
            }
        }
    } else {
        AsyncImage(
            model = imageUrl,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            onState = { state ->
                if (state is AsyncImagePainter.State.Error) failed = true
            },
        )
    }
}

@Composable
fun MonoDetailCover(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    placeholderIcon: ImageVector = Icons.Default.MusicNote,
) {
    MonoFallbackImage(
        imageUrl = imageUrl,
        modifier = modifier.fillMaxSize(),
        placeholderIcon = placeholderIcon,
        placeholderIconSize = n(100),
    )
}
