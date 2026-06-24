package com.lightphone.spotify.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.composed
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.lightphone.spotify.ui.theme.MonoColors
import com.lightphone.spotify.ui.theme.PublicSans
import com.lightphone.spotify.ui.theme.n
import com.lightphone.spotify.ui.theme.nSp

/**
 * Flat, ripple-free tap — matches the template's HapticPressable behavior.
 * Implemented with [composed] so it can be used conditionally in modifier chains
 * (e.g. `.then(if (x) Modifier.tap { } else Modifier)`) without breaking the
 * composable call order.
 */
fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        enabled = enabled,
        onClick = onClick,
    )
}

/** The single styled text primitive (Public Sans, white by default). */
@Composable
fun StyledText(
    text: String,
    modifier: Modifier = Modifier,
    size: Number = 16,
    lineHeight: Number? = null,
    color: Color = MonoColors.Foreground,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    textDecoration: TextDecoration? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = nSp(size),
        lineHeight = if (lineHeight != null) nSp(lineHeight) else TextUnit.Unspecified,
        fontFamily = PublicSans,
        fontWeight = FontWeight.Normal,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign,
        textDecoration = textDecoration,
    )
}

@Composable
fun MonoHeader(
    title: String,
    modifier: Modifier = Modifier,
    hideBackButton: Boolean = false,
    onBack: (() -> Unit)? = null,
    leftIcon: ImageVector? = null,
    onLeftIconClick: (() -> Unit)? = null,
    rightIcon: ImageVector? = null,
    onRightIconClick: (() -> Unit)? = null,
    rightIconVisible: Boolean = true,
    rightLoading: Boolean = false,
    onTitleClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MonoColors.Background)
            .padding(horizontal = n(22), vertical = n(5)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left slot — fixed 32-wide box: back arrow, custom icon, or empty.
        when {
            !hideBackButton -> HeaderIconSlot(
                icon = Icons.AutoMirrored.Filled.ArrowBackIos,
                onClick = { onBack?.invoke() },
            )
            leftIcon != null -> HeaderIconSlot(icon = leftIcon, onClick = { onLeftIconClick?.invoke() })
            else -> Spacer(Modifier.size(n(32)))
        }

        StyledText(
            text = title,
            size = 20,
            color = MonoColors.Foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = n(4))
                .then(if (onTitleClick != null) Modifier.tap(onClick = onTitleClick) else Modifier),
        )

        // Right slot.
        when {
            rightLoading -> Box(Modifier.size(n(32)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(n(20)),
                    color = MonoColors.Foreground,
                    strokeWidth = 2.dp,
                )
            }
            rightIconVisible && rightIcon != null -> HeaderIconSlot(
                icon = rightIcon,
                onClick = { onRightIconClick?.invoke() },
            )
            else -> Spacer(Modifier.size(n(32)))
        }
    }
}

@Composable
private fun HeaderIconSlot(icon: ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(n(32))
            .tap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MonoColors.Foreground, modifier = Modifier.size(n(28)))
    }
}

/**
 * Screen frame: black canvas, optional header, then a content area inset by
 * [horizontalPadding] / [topPadding] with [contentGap] between stacked children.
 * Mirrors mono's ContentContainer (the scroll view itself lives inside).
 */
@Composable
fun MonoContentContainer(
    title: String?,
    modifier: Modifier = Modifier,
    hideBackButton: Boolean = true,
    onBack: (() -> Unit)? = null,
    leftIcon: ImageVector? = null,
    onLeftIconClick: (() -> Unit)? = null,
    rightIcon: ImageVector? = null,
    onRightIconClick: (() -> Unit)? = null,
    rightIconVisible: Boolean = true,
    rightLoading: Boolean = false,
    onTitleClick: (() -> Unit)? = null,
    horizontalPadding: Dp = n(37),
    topPadding: Dp = n(14),
    contentGap: Dp = n(47),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MonoColors.Background),
    ) {
        if (title != null) {
            MonoHeader(
                title = title,
                hideBackButton = hideBackButton,
                onBack = onBack,
                leftIcon = leftIcon,
                onLeftIconClick = onLeftIconClick,
                rightIcon = rightIcon,
                onRightIconClick = onRightIconClick,
                rightIconVisible = rightIconVisible,
                rightLoading = rightLoading,
                onTitleClick = onTitleClick,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = horizontalPadding, end = horizontalPadding)
                .padding(top = topPadding),
            verticalArrangement = Arrangement.spacedBy(contentGap),
        ) {
            content()
        }
    }
}

@Composable
fun MonoMediaListItem(
    primaryText: String,
    modifier: Modifier = Modifier,
    secondaryText: String? = null,
    imageUrl: String? = null,
    placeholderIcon: ImageVector = Icons.Default.MusicNote,
    showImage: Boolean = true,
    disabled: Boolean = false,
    /** False for library rows — avoids crossfade jank during fast scroll. */
    crossfadeImage: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = n(50))
            .tap(enabled = !disabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showImage) {
            MonoFallbackImage(
                imageUrl = imageUrl,
                placeholderIcon = placeholderIcon,
                placeholderIconSize = n(24),
                modifier = Modifier.size(n(50)),
                disabled = disabled,
                crossfade = crossfadeImage,
                decodeSize = if (crossfadeImage) null else n(50),
            )
            Spacer(Modifier.width(n(15)))
        }
        Column(
            Modifier
                .weight(1f)
                .padding(end = n(10)),
        ) {
            StyledText(
                primaryText,
                size = 22,
                lineHeight = 24,
                color = if (disabled) MonoColors.DisabledIcon else MonoColors.Foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (secondaryText != null) {
                StyledText(
                    secondaryText,
                    size = 16,
                    lineHeight = 18,
                    color = if (disabled) MonoColors.DisabledIcon else MonoColors.Foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun MonoTrackListItem(
    trackNumber: Int,
    name: String,
    artists: String,
    durationMs: Long,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .tap(onClick = onClick),
        verticalAlignment = Alignment.Top,
    ) {
        StyledText(
            "$trackNumber.",
            size = 26,
            color = MonoColors.Foreground,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .width(n(56))
                .padding(end = n(8)),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(end = n(10)),
        ) {
            StyledText(
                name,
                size = 26,
                color = MonoColors.Foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StyledText(
                "$artists \u00B7 ${formatDuration(durationMs)}",
                size = 16,
                lineHeight = 18,
                color = MonoColors.Foreground,
                modifier = Modifier.padding(bottom = n(6)),
            )
        }
    }
}

@Composable
fun MonoStyledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    StyledText(
        text = text,
        size = 30,
        color = MonoColors.Foreground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textDecoration = if (selected) TextDecoration.Underline else null,
        modifier = modifier.tap(onClick = onClick),
    )
}

@Composable
fun MonoToggleSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .tap { onCheckedChange(!checked) }
            .padding(top = n(9)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.padding(start = n(8.5), end = n(20), top = n(13)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (checked) {
                Box(Modifier.width(n(14.5)).height(n(2.22)).background(MonoColors.Foreground))
                Box(Modifier.size(n(9.8)).background(MonoColors.Foreground, CircleShape))
            } else {
                Box(Modifier.size(n(9.8)).border(n(2.5), MonoColors.Foreground, CircleShape))
                Box(Modifier.width(n(14.5)).height(n(2.22)).background(MonoColors.Foreground))
            }
        }
        StyledText(label, size = 30, color = MonoColors.Foreground, modifier = Modifier.weight(1f))
    }
}

/** Two-line selector row (label above, current value below) — template's SelectorButton. */
@Composable
fun MonoSelectorButton(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.tap(onClick = onClick)) {
        StyledText(label, size = 20, lineHeight = 20, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = n(7.5)))
        StyledText(value, size = 30, modifier = Modifier.padding(bottom = n(10)))
    }
}

fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

fun formatTime(positionMs: Long): String = formatDuration(positionMs)
