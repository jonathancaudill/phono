package com.lightphone.spotify.ui.light

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.thelightphone.sdk.ui.LightSurfaceScheme
import com.thelightphone.sdk.ui.LightThemeTokens

/** App-only semantic colors not provided by Light SDK tokens. */
object PhonoSemanticColors {
    val InactiveTab: Color
        @Composable get() = when (LightThemeTokens.surfaceScheme) {
            LightSurfaceScheme.Dark -> Color(0xFF6E6E6E)
            LightSurfaceScheme.Light -> Color(0xFF919191)
        }

    val Placeholder: Color
        @Composable get() = when (LightThemeTokens.surfaceScheme) {
            LightSurfaceScheme.Dark -> Color(0xFF888888)
            LightSurfaceScheme.Light -> Color(0xFF777777)
        }

    val PlaceholderBg: Color
        @Composable get() = when (LightThemeTokens.surfaceScheme) {
            LightSurfaceScheme.Dark -> Color(0xFF282828)
            LightSurfaceScheme.Light -> Color(0xFFD7D7D7)
        }

    val Error: Color
        @Composable get() = Color(0xFFFF6B6B)

    val Warning: Color
        @Composable get() = Color(0xFFFFB74D)

    val OfflineStripBg: Color
        @Composable get() = when (LightThemeTokens.surfaceScheme) {
            LightSurfaceScheme.Dark -> Color.White
            LightSurfaceScheme.Light -> Color.Black
        }

    val OfflineStripFg: Color
        @Composable get() = when (LightThemeTokens.surfaceScheme) {
            LightSurfaceScheme.Dark -> Color.Black
            LightSurfaceScheme.Light -> Color.White
        }

    val DisabledIcon: Color
        @Composable get() = when (LightThemeTokens.surfaceScheme) {
            LightSurfaceScheme.Dark -> Color(0xFF666666)
            LightSurfaceScheme.Light -> Color(0xFF999999)
        }
}
