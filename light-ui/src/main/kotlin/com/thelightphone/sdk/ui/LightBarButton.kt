package com.thelightphone.sdk.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow

sealed interface LightBarButton {
    val onClick: (() -> Unit)?
    val contentDescription: String?

    data class Text(
        val text: String,
        override val contentDescription: String? = null,
        override val onClick: (() -> Unit)?,
    ) : LightBarButton

    /**).
     * used for custom icons (your own painter
     *
     * for LightOS icons, prefer [LightBarButton.LightIcon].
     */
    data class Icon(
        val painter: Painter,
        override val onClick: (() -> Unit)?,
        override val contentDescription: String? = null,
        val sizeUnits: Float = LightBarButtonDefaults.ICON_SIZE_UNITS,
        val tint: Color? = null,
    ) : LightBarButton

    /**
     * LightOS icon (from [LightIcons]).
     */
    data class LightIcon(
        val icon: LightIconConfiguration,
        override val onClick: (() -> Unit)?,
        override val contentDescription: String? = icon.name,
        val sizeUnits: Float = LightBarButtonDefaults.ICON_SIZE_UNITS,
        val tint: Color? = null,
    ) : LightBarButton
}

object LightBarButtonDefaults {
    const val ICON_SIZE_UNITS = 2f
}

typealias LightTopBarButton = LightBarButton
typealias LightBottomBarItem = LightBarButton

/** Returns a copy of this button with [LightBarButton.onClick] cleared (for parent-owned hit targets). */
internal fun LightBarButton.withoutOnClick(): LightBarButton = when (this) {
    is LightBarButton.Text -> copy(onClick = null)
    is LightBarButton.Icon -> copy(onClick = null)
    is LightBarButton.LightIcon -> copy(onClick = null)
}

@Composable
internal fun LightBarButtonView(
    button: LightBarButton?,
    heightUnits: Float,
    iconSizeUnits: Float = LightBarButtonDefaults.ICON_SIZE_UNITS,
    textVariant: LightTextVariant,
    useSpacerWhenNull: Boolean,
) {
    if (button == null) {
        if (useSpacerWhenNull) {
            LightIcon(
                icon = LightIcons.SPACER,
                size = iconSizeUnits,
                contentDescription = null,
            )
        }
        return
    }

    val barHeight = heightUnits.gridUnitsAsDp()
    val clickable = button.onClick

    when (button) {
        is LightBarButton.Text -> {
            Box(
                modifier = Modifier
                    .height(barHeight)
                    .widthIn(min = barHeight)
                    .then(if (clickable != null) Modifier.lightClickable(onClick = clickable) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                LightText(
                    text = button.text,
                    variant = textVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        is LightBarButton.Icon -> {
            val iconSize = button.sizeUnits.gridUnitsAsDp()
            val hitWidth = maxOf(iconSize, barHeight)
            val contentColor = button.tint ?: LightThemeTokens.colors.content
            Box(
                modifier = Modifier
                    .size(width = hitWidth, height = barHeight)
                    .then(if (clickable != null) Modifier.lightClickable(onClick = clickable) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = button.painter,
                    contentDescription = button.contentDescription,
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(contentColor),
                    modifier = Modifier.size(iconSize),
                )
            }
        }

        is LightBarButton.LightIcon -> {
            val iconSize = button.sizeUnits.gridUnitsAsDp()
            val hitWidth = maxOf(iconSize, barHeight)
            Box(
                modifier = Modifier
                    .size(width = hitWidth, height = barHeight)
                    .then(if (clickable != null) Modifier.lightClickable(onClick = clickable) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                LightIcon(
                    icon = button.icon,
                    size = button.sizeUnits,
                    tint = button.tint,
                    contentDescription = button.contentDescription,
                )
            }
        }
    }
}
