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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.lightphone.spotify.ui.theme.PhonoColors
import com.lightphone.spotify.ui.theme.n

/**
 * Square artwork with grey placeholder when there's no URL or the load fails.
 * Used on Now Playing only; library/search/detail lists are text-only.
 */
@Composable
fun PhonoFallbackImage(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    placeholderIcon: ImageVector = Icons.Default.MusicNote,
    placeholderIconSize: Dp = n(100),
    placeholderText: String? = null,
    disabled: Boolean = false,
    contentDescription: String? = null,
    crossfade: Boolean = true,
    decodeSize: Dp? = null,
) {
    var failed by remember(imageUrl) { mutableStateOf(false) }
    val iconTint = if (disabled) PhonoColors.DisabledIcon else PhonoColors.Foreground

    if (imageUrl.isNullOrBlank() || failed) {
        Box(
            modifier = modifier.background(PhonoColors.PlaceholderBg),
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
        val context = LocalContext.current
        val density = LocalDensity.current
        val request = remember(imageUrl, crossfade, decodeSize, density) {
            val builder = ImageRequest.Builder(context)
                .data(imageUrl)
                .crossfade(crossfade)
            if (decodeSize != null) {
                builder.size(with(density) { decodeSize.roundToPx() })
            }
            builder.build()
        }
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = modifier.background(PhonoColors.PlaceholderBg),
            contentScale = ContentScale.Crop,
            onState = { state ->
                if (state is AsyncImagePainter.State.Error) failed = true
            },
        )
    }
}

@Composable
fun PhonoDetailCover(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    placeholderIcon: ImageVector = Icons.Default.MusicNote,
) {
    PhonoFallbackImage(
        imageUrl = imageUrl,
        modifier = modifier.fillMaxSize(),
        placeholderIcon = placeholderIcon,
        placeholderIconSize = n(100),
    )
}
