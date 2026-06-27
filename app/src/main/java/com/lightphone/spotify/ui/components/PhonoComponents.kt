package com.lightphone.spotify.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import com.lightphone.spotify.ui.theme.PhonoColors
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

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tapWithLongPress(
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
): Modifier = composed {
    if (onLongClick != null) {
        combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    } else {
        clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
    }
}

/** The single styled text primitive (Public Sans, white by default). */
@Composable
fun StyledText(
    text: String,
    modifier: Modifier = Modifier,
    size: Number = 16,
    lineHeight: Number? = null,
    color: Color = PhonoColors.Foreground,
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
fun PhonoHeader(
    title: String? = null,
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
    titleContent: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(PhonoColors.Background)
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

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = n(4)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                titleContent != null -> titleContent()
                title != null -> StyledText(
                    text = title,
                    size = 20,
                    color = PhonoColors.Foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = if (onTitleClick != null) {
                        Modifier.tap(onClick = onTitleClick)
                    } else {
                        Modifier
                    },
                )
            }
        }

        // Right slot.
        when {
            rightLoading -> Box(Modifier.size(n(32)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(n(20)),
                    color = PhonoColors.Foreground,
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
        Icon(icon, contentDescription = null, tint = PhonoColors.Foreground, modifier = Modifier.size(n(28)))
    }
}

/**
 * Screen frame: black canvas, optional header, then a content area inset by
 * [horizontalPadding] / [topPadding] with [contentGap] between stacked children.
 * Mirrors phono's ContentContainer (the scroll view itself lives inside).
 */
@Composable
fun PhonoContentContainer(
    title: String? = null,
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
    titleContent: @Composable (() -> Unit)? = null,
    horizontalPadding: Dp = n(37),
    topPadding: Dp = n(14),
    contentGap: Dp = n(47),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PhonoColors.Background),
    ) {
        if (title != null || titleContent != null) {
            PhonoHeader(
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
                titleContent = titleContent,
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
fun PhonoSquareCheckbox(
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val borderColor = if (enabled) PhonoColors.Foreground else PhonoColors.DisabledIcon
    val fillColor = if (enabled) PhonoColors.Foreground else PhonoColors.DisabledIcon
    val checkColor = PhonoColors.Background
    Box(
        modifier = modifier
            .size(n(20))
            .then(if (checked) Modifier.background(fillColor) else Modifier)
            .border(n(2.5), borderColor),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = checkColor,
                modifier = Modifier.size(n(14)),
            )
        }
    }
}

@Composable
fun PhonoMediaListItem(
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
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = n(50))
            .tapWithLongPress(enabled = !disabled, onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showImage) {
            PhonoFallbackImage(
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
                color = if (disabled) PhonoColors.DisabledIcon else PhonoColors.Foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (secondaryText != null) {
                StyledText(
                    secondaryText,
                    size = 16,
                    lineHeight = 18,
                    color = if (disabled) PhonoColors.DisabledIcon else PhonoColors.Foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val TrackListLeadingWidth = n(56)
private val TrackEditReorderEndPadding = n(14)

data class PhonoTrackEditActions(
    val mutating: Boolean = false,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val onRemove: () -> Unit,
    val onMoveUp: () -> Unit,
    val onMoveDown: () -> Unit,
)

@Composable
fun PhonoTrackListItem(
    trackNumber: Int? = null,
    name: String,
    artists: String,
    durationMs: Long,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    editActions: PhonoTrackEditActions? = null,
) {
    val interactiveModifier = if (editActions == null) {
        Modifier.tapWithLongPress(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(interactiveModifier),
        verticalAlignment = Alignment.Top,
    ) {
        when {
            editActions != null -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .width(TrackListLeadingWidth)
                        .padding(end = n(8)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Cancel,
                        contentDescription = "Remove track",
                        tint = if (!editActions.mutating) {
                            PhonoColors.Foreground
                        } else {
                            PhonoColors.DisabledIcon
                        },
                        modifier = Modifier
                            .size(n(20))
                            .tap(enabled = !editActions.mutating, onClick = editActions.onRemove),
                    )
                }
            }
            trackNumber != null -> {
                StyledText(
                    "$trackNumber.",
                    size = 26,
                    color = PhonoColors.Foreground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .width(TrackListLeadingWidth)
                        .padding(end = n(8)),
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(end = if (editActions != null) n(8) else n(10)),
        ) {
            StyledText(
                name,
                size = 26,
                color = PhonoColors.Foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StyledText(
                "$artists \u00B7 ${formatDuration(durationMs)}",
                size = 16,
                lineHeight = 18,
                color = PhonoColors.Foreground,
                modifier = Modifier.padding(bottom = n(6)),
            )
        }
        if (editActions != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(end = TrackEditReorderEndPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val upEnabled = editActions.canMoveUp && !editActions.mutating
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = "Move up",
                    tint = if (upEnabled) PhonoColors.Foreground else PhonoColors.Placeholder,
                    modifier = Modifier
                        .size(n(32))
                        .tap(enabled = upEnabled, onClick = editActions.onMoveUp),
                )
                Spacer(Modifier.width(n(4)))
                val downEnabled = editActions.canMoveDown && !editActions.mutating
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "Move down",
                    tint = if (downEnabled) PhonoColors.Foreground else PhonoColors.Placeholder,
                    modifier = Modifier
                        .size(n(32))
                        .tap(enabled = downEnabled, onClick = editActions.onMoveDown),
                )
            }
        }
    }
}

@Composable
fun PhonoStyledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    StyledText(
        text = text,
        size = 30,
        color = PhonoColors.Foreground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textDecoration = if (selected) TextDecoration.Underline else null,
        modifier = modifier.tap(onClick = onClick),
    )
}

@Composable
fun PhonoToggleSwitch(
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
                Box(Modifier.width(n(14.5)).height(n(2.22)).background(PhonoColors.Foreground))
                Box(Modifier.size(n(9.8)).background(PhonoColors.Foreground, CircleShape))
            } else {
                Box(Modifier.size(n(9.8)).border(n(2.5), PhonoColors.Foreground, CircleShape))
                Box(Modifier.width(n(14.5)).height(n(2.22)).background(PhonoColors.Foreground))
            }
        }
        StyledText(label, size = 30, color = PhonoColors.Foreground, modifier = Modifier.weight(1f))
    }
}

/** Two-line selector row (label above, current value below) — template's SelectorButton. */
@Composable
fun PhonoSelectorButton(
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
