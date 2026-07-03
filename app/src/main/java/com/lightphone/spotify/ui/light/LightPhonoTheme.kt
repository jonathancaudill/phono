package com.lightphone.spotify.ui.light

import androidx.compose.runtime.Composable
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors

@Composable
fun LightPhonoTheme(content: @Composable () -> Unit) {
    LightTheme(colors = LightThemeColors.Dark, content = content)
}
