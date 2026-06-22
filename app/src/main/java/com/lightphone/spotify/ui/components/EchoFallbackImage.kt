package com.lightphone.spotify.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.lightphone.spotify.ui.theme.EchoColors

@Composable
fun EchoFallbackImage(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    placeholderIcon: ImageVector = Icons.Default.MusicNote,
    contentDescription: String? = null,
) {
    if (imageUrl.isNullOrBlank()) {
        Box(
            modifier = modifier.background(EchoColors.PlaceholderBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(placeholderIcon, contentDescription = contentDescription, tint = EchoColors.Foreground)
        }
    } else {
        AsyncImage(
            model = imageUrl,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
fun EchoDetailCover(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    placeholderIcon: ImageVector = Icons.Default.MusicNote,
) {
    EchoFallbackImage(
        imageUrl = imageUrl,
        modifier = modifier.fillMaxSize(),
        placeholderIcon = placeholderIcon,
    )
}
