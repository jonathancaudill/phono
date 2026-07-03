package com.thelightphone.sdk.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Matches LightOS theme behavior: icons are colored according to [LightThemeTokens.colors.content].
 *
 */
enum class LightSurfaceScheme {
    Dark,
    Light,
}

private const val DEFAULT_SIZE = 2f

@Composable
fun LightIcon(
    icon: LightIconConfiguration,
    modifier: Modifier = Modifier,
    width: Float = DEFAULT_SIZE,
    height: Float = DEFAULT_SIZE,
    size: Float? = null,
    tint: Color? = null,
    contentDescription: String? = icon.name,
) {
    val resolvedWidth = size ?: width
    val resolvedHeight = size ?: height
    val contentColor = tint ?: LightThemeTokens.colors.content
    val drawableId = when (LightThemeTokens.surfaceScheme) {
        LightSurfaceScheme.Dark -> icon.darkModeResource
        LightSurfaceScheme.Light -> icon.lightModeResource
    }
    Icon(
        painter = painterResource(drawableId),
        contentDescription = contentDescription,
        tint = contentColor,
        modifier = modifier
            .size(resolvedWidth.gridUnitsAsDp(), resolvedHeight.gridUnitsAsDp())
            .semantics {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            },
    )
}
