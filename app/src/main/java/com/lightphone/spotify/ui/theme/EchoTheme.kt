package com.lightphone.spotify.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object EchoColors {
    val Background = Color.Black
    val Foreground = Color.White
    val InactiveTab = Color(0xFF6E6E6E)
    val Placeholder = Color(0xFF888888)
    val PlaceholderBg = Color(0xFF282828)
    val Error = Color(0xFFFF6B6B)
    val Warning = Color(0xFFFFB74D)
    val OfflineStripBg = Color.White
    val OfflineStripFg = Color.Black
}

private val EchoTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 18.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 24.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
    ),
)

@Composable
fun EchoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = EchoColors.Background,
            onBackground = EchoColors.Foreground,
            surface = EchoColors.Background,
            onSurface = EchoColors.Foreground,
            primary = EchoColors.Foreground,
            onPrimary = EchoColors.Background,
        ),
        typography = EchoTypography,
        content = content,
    )
}
