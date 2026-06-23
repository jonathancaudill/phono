package com.lightphone.spotify.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.lightphone.spotify.R

/**
 * Palette mirrors the LightOS template / mono (black canvas, white ink, a single
 * muted grey for inactive tabs, a darker grey image placeholder). These are the
 * only colors the reference uses.
 */
object MonoColors {
    val Background = Color.Black
    val Foreground = Color.White
    val InactiveTab = Color(0xFF6E6E6E)
    val Placeholder = Color(0xFF888888)
    val PlaceholderBg = Color(0xFF282828)
    val Error = Color(0xFFFF6B6B)
    val Warning = Color(0xFFFFB74D)
    val OfflineStripBg = Color.White
    val OfflineStripFg = Color.Black
    val DisabledIcon = Color(0xFF666666)
}

/** Public Sans Regular — the template's single typeface. */
val PublicSans = FontFamily(
    Font(R.font.public_sans_regular, FontWeight.Normal),
)

@Composable
fun MonoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = MonoColors.Background,
            onBackground = MonoColors.Foreground,
            surface = MonoColors.Background,
            onSurface = MonoColors.Foreground,
            primary = MonoColors.Foreground,
            onPrimary = MonoColors.Background,
        ),
        content = content,
    )
}
