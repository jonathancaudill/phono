package com.lightphone.spotify.ui.phono

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lightphone.spotify.ui.light.lightIconFor
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.consumeScrimTouches
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import androidx.compose.ui.graphics.vector.rememberVectorPainter

@Composable
fun PhonoScreenShell(
    modifier: Modifier = Modifier,
    title: String? = null,
    hideBackButton: Boolean = true,
    onBack: (() -> Unit)? = null,
    leftIcon: ImageVector? = null,
    onLeftIconClick: (() -> Unit)? = null,
    rightIcon: ImageVector? = null,
    rightLightIcon: LightIconConfiguration? = null,
    onRightIconClick: (() -> Unit)? = null,
    rightIconVisible: Boolean = true,
    rightLoading: Boolean = false,
    onTitleClick: (() -> Unit)? = null,
    titleContent: @Composable (() -> Unit)? = null,
    horizontalPadding: Dp = legacyNToGridDp(20),
    topPadding: Dp = 0.dp,
    contentGap: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LightThemeTokens.colors
    Box(
        modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Box(Modifier.matchParentSize().consumeScrimTouches())
        Column(Modifier.fillMaxSize()) {
        val leftButton = when {
            !hideBackButton && onBack != null -> LightBarButton.LightIcon(LightIcons.BACK, onClick = onBack)
            leftIcon != null && onLeftIconClick != null -> {
                val lightIcon = lightIconFor(leftIcon)
                if (lightIcon != null) {
                    LightBarButton.LightIcon(lightIcon, onClick = onLeftIconClick)
                } else {
                    LightBarButton.Icon(
                        painter = rememberVectorPainter(leftIcon),
                        onClick = onLeftIconClick,
                    )
                }
            }
            else -> null
        }

        val rightButton = when {
            rightLoading -> null
            rightIconVisible && rightLightIcon != null && onRightIconClick != null ->
                LightBarButton.LightIcon(rightLightIcon, onClick = onRightIconClick)
            rightIconVisible && rightIcon != null && onRightIconClick != null -> {
                val lightIcon = lightIconFor(rightIcon)
                if (lightIcon != null) {
                    LightBarButton.LightIcon(lightIcon, onClick = onRightIconClick)
                } else {
                    LightBarButton.Icon(
                        painter = rememberVectorPainter(rightIcon),
                        onClick = onRightIconClick,
                    )
                }
            }
            else -> null
        }

        val center = when {
            titleContent != null -> null
            title != null -> LightTopBarCenter.Text(
                text = title,
                onClick = onTitleClick,
            )
            else -> null
        }

        Box(Modifier.fillMaxWidth()) {
            LightTopBar(
                leftButton = leftButton,
                center = if (titleContent != null) null else center,
                rightButton = rightButton,
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            if (titleContent != null) {
                Box(
                    Modifier
                        .matchParentSize()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    titleContent()
                }
            }
            if (rightLoading) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 1f.gridUnitsAsDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(legacyNToGridDp(20)),
                        color = colors.content,
                        strokeWidth = 2.dp,
                    )
                }
            }
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = horizontalPadding, end = horizontalPadding)
                .padding(top = topPadding),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(contentGap),
        ) {
            content()
        }
        }
    }
}

@Composable
fun PhonoTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    underline: Boolean = selected,
) {
    com.thelightphone.sdk.ui.LightText(
        text = text,
        variant = com.thelightphone.sdk.ui.LightTextVariant.Button,
        underline = underline,
        modifier = modifier.lightClickable(onClick = onClick),
    )
}

@Composable
fun PhonoHeaderIcon(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val lightIcon = lightIconFor(icon)
    val colors = LightThemeTokens.colors
    if (lightIcon != null) {
        com.thelightphone.sdk.ui.LightIcon(
            icon = lightIcon,
            modifier = modifier.lightClickable(onClick = onClick),
            contentDescription = contentDescription,
        )
    } else {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = colors.content,
            modifier = modifier.lightClickable(onClick = onClick),
        )
    }
}
