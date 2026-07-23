package com.lightphone.spotify.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoHeaderIcon
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

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
        lightClickable(enabled = enabled, onClick = onClick)
    }
}

@Composable
fun PhonoSquareCheckbox(
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LightThemeTokens.colors
    val borderColor = if (enabled) colors.content else PhonoSemanticColors.DisabledIcon
    val fillColor = if (enabled) colors.content else PhonoSemanticColors.DisabledIcon
    Box(
        modifier = modifier
            .size(legacyNToGridDp(20))
            .then(if (checked) Modifier.background(fillColor) else Modifier)
            .border(legacyNToGridDp(2.5f), borderColor),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = colors.background,
                modifier = Modifier.size(legacyNToGridDp(14)),
            )
        }
    }
}

/** Leading Cancel affordance matching playlist-edit track rows. */
@Composable
fun PhonoEditDeleteLeading(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String = "Remove",
) {
    val colors = LightThemeTokens.colors
    Box(
        modifier = modifier
            .width(TrackListLeadingWidth)
            .padding(end = legacyNToGridDp(8)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Cancel,
            contentDescription = contentDescription,
            tint = if (enabled) colors.content else PhonoSemanticColors.DisabledIcon,
            modifier = Modifier
                .size(legacyNToGridDp(20))
                .lightClickable(enabled = enabled, onClick = onDelete),
        )
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
    crossfadeImage: Boolean = true,
    /** When set, shows playlist-edit Cancel leading and ignores row click. */
    onEditDelete: (() -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = LightThemeTokens.colors
    val textColor = if (disabled) PhonoSemanticColors.DisabledIcon else colors.content
    val editing = onEditDelete != null
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = legacyNToGridDp(54))
            .then(
                if (editing) Modifier
                else Modifier.tapWithLongPress(enabled = !disabled, onClick = onClick, onLongClick = onLongClick),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onEditDelete != null) {
            PhonoEditDeleteLeading(onDelete = onEditDelete)
        }
        if (showImage) {
            PhonoFallbackImage(
                imageUrl = imageUrl,
                placeholderIcon = placeholderIcon,
                placeholderIconSize = legacyNToGridDp(24),
                modifier = Modifier.size(legacyNToGridDp(50)),
                disabled = disabled,
                crossfade = crossfadeImage,
                decodeSize = if (crossfadeImage) null else legacyNToGridDp(50),
            )
            Spacer(modifier.width(legacyNToGridDp(15)))
        }
        Column(
            Modifier
                .weight(1f)
                .padding(end = legacyNToGridDp(10)),
        ) {
            LightText(
                text = primaryText,
                variant = LightTextVariant.Copy,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (secondaryText != null) {
                LightText(
                    text = secondaryText,
                    variant = LightTextVariant.Detail,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val TrackListLeadingWidth @Composable get() = legacyNToGridDp(56)
private val TrackEditReorderEndPadding @Composable get() = legacyNToGridDp(14)

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
    val colors = LightThemeTokens.colors
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
                        .padding(end = legacyNToGridDp(8)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Cancel,
                        contentDescription = "Remove track",
                        tint = if (!editActions.mutating) colors.content else PhonoSemanticColors.DisabledIcon,
                        modifier = Modifier
                            .size(legacyNToGridDp(20))
                            .lightClickable(
                                enabled = !editActions.mutating,
                                onClick = editActions.onRemove,
                            ),
                    )
                }
            }
            trackNumber != null -> {
                LightText(
                    text = "$trackNumber.",
                    variant = LightTextVariant.Copy,
                    align = TextAlign.Center,
                    modifier = Modifier
                        .width(TrackListLeadingWidth)
                        .padding(end = legacyNToGridDp(8)),
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(end = if (editActions != null) legacyNToGridDp(8) else legacyNToGridDp(10)),
        ) {
            LightText(
                text = name,
                variant = LightTextVariant.Copy,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LightText(
                text = "$artists · ${formatDuration(durationMs)}",
                variant = LightTextVariant.Detail,
                modifier = Modifier.padding(bottom = legacyNToGridDp(6)),
            )
        }
        if (editActions != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(end = TrackEditReorderEndPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhonoHeaderIcon(
                    icon = Icons.Default.KeyboardArrowUp,
                    onClick = editActions.onMoveUp,
                    modifier = Modifier.size(legacyNToGridDp(32)),
                    contentDescription = "Move up",
                )
                Spacer(Modifier.width(legacyNToGridDp(4)))
                PhonoHeaderIcon(
                    icon = Icons.Default.KeyboardArrowDown,
                    onClick = editActions.onMoveDown,
                    modifier = Modifier.size(legacyNToGridDp(32)),
                    contentDescription = "Move down",
                )
            }
        }
    }
}

@Composable
fun PhonoToggleSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LightThemeTokens.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .lightClickable { onCheckedChange(!checked) }
            .padding(top = legacyNToGridDp(9)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.padding(
                start = legacyNToGridDp(8.5f),
                end = legacyNToGridDp(20),
                top = legacyNToGridDp(13),
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (checked) {
                Box(Modifier.width(legacyNToGridDp(14.5f)).height(legacyNToGridDp(2.22f)).background(colors.content))
                Box(Modifier.size(legacyNToGridDp(9.8f)).background(colors.content, CircleShape))
            } else {
                Box(Modifier.size(legacyNToGridDp(9.8f)).border(legacyNToGridDp(2.5f), colors.content, CircleShape))
                Box(Modifier.width(legacyNToGridDp(14.5f)).height(legacyNToGridDp(2.22f)).background(colors.content))
            }
        }
        LightText(
            text = label,
            variant = LightTextVariant.Button,
            modifier = Modifier.weight(1f),
        )
    }
}

fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

fun formatTime(positionMs: Long): String = formatDuration(positionMs)