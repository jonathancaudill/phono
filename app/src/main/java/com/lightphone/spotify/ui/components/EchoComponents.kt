package com.lightphone.spotify.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lightphone.spotify.ui.theme.EchoColors

@Composable
fun EchoHeader(
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
            .background(EchoColors.Background)
            .padding(horizontal = 22.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            hideBackButton && leftIcon != null -> {
                IconButton(onClick = { onLeftIconClick?.invoke() }) {
                    Icon(leftIcon, contentDescription = null, tint = EchoColors.Foreground, modifier = Modifier.size(28.dp))
                }
            }
            !hideBackButton -> {
                IconButton(onClick = { onBack?.invoke() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBackIos,
                        contentDescription = "Back",
                        tint = EchoColors.Foreground,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            else -> Spacer(Modifier.width(32.dp))
        }

        val titleModifier = Modifier
            .weight(1f)
            .then(if (onTitleClick != null) Modifier.clickable { onTitleClick() } else Modifier)
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = EchoColors.Foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = titleModifier.padding(horizontal = 4.dp),
        )

        when {
            rightLoading -> {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = EchoColors.Foreground,
                        strokeWidth = 2.dp,
                    )
                }
            }
            rightIconVisible && rightIcon != null -> {
                IconButton(onClick = { onRightIconClick?.invoke() }) {
                    Icon(rightIcon, contentDescription = null, tint = EchoColors.Foreground, modifier = Modifier.size(28.dp))
                }
            }
            else -> Spacer(Modifier.width(32.dp))
        }
    }
}

@Composable
fun EchoContentContainer(
    title: String,
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
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(EchoColors.Background),
    ) {
        EchoHeader(
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
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 14.dp),
        ) {
            content()
        }
    }
}

@Composable
fun EchoMediaListItem(
    primaryText: String,
    modifier: Modifier = Modifier,
    secondaryText: String? = null,
    imageUrl: String? = null,
    placeholderIcon: ImageVector = Icons.Default.MusicNote,
    disabled: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = !disabled) { onClick() }
            .padding(vertical = 0.dp)
            .then(if (disabled) Modifier else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EchoFallbackImage(
            imageUrl = imageUrl,
            placeholderIcon = placeholderIcon,
            modifier = Modifier.size(50.dp),
            contentDescription = null,
        )
        Spacer(Modifier.width(15.dp))
        Column(Modifier.weight(1f)) {
            Text(
                primaryText,
                style = MaterialTheme.typography.bodyLarge,
                color = if (disabled) EchoColors.InactiveTab else EchoColors.Foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (secondaryText != null) {
                Text(
                    secondaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (disabled) EchoColors.InactiveTab else EchoColors.Placeholder,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
fun EchoTrackListItem(
    trackNumber: Int,
    name: String,
    artists: String,
    durationMs: Long,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            "$trackNumber.",
            style = MaterialTheme.typography.titleMedium.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize * 1.18f),
            color = EchoColors.Foreground,
            modifier = Modifier.width(56.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize * 1.18f),
                color = EchoColors.Foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "$artists · ${formatDuration(durationMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = EchoColors.Placeholder,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

fun formatTime(positionMs: Long): String = formatDuration(positionMs)

@Composable
fun EchoSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = EchoColors.Placeholder,
        modifier = modifier.padding(top = 24.dp, bottom = 12.dp),
    )
}

@Composable
fun EchoStyledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    underline: Boolean = false,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = EchoColors.Foreground,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp)
            .then(if (underline) Modifier else Modifier),
        textDecoration = if (underline) {
            androidx.compose.ui.text.style.TextDecoration.Underline
        } else {
            null
        },
    )
}

@Composable
fun EchoToggleSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(top = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 20.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (checked) {
                Box(
                    Modifier
                        .width(15.dp)
                        .height(2.dp)
                        .background(EchoColors.Foreground),
                )
                Box(
                    Modifier
                        .size(10.dp)
                        .background(EchoColors.Foreground, CircleShape),
                )
            } else {
                Box(
                    Modifier
                        .size(10.dp)
                        .border(2.dp, EchoColors.Foreground, CircleShape),
                )
                Box(
                    Modifier
                        .width(15.dp)
                        .height(2.dp)
                        .background(EchoColors.Foreground),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = EchoColors.Foreground,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun EchoVolumeSlider(
    volumePercent: Int,
    onVolumeChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Volume",
                style = MaterialTheme.typography.bodyLarge,
                color = EchoColors.Foreground,
                modifier = Modifier.weight(1f),
            )
            Text(
                "$volumePercent%",
                style = MaterialTheme.typography.bodyMedium,
                color = EchoColors.Placeholder,
            )
        }
        Slider(
            value = volumePercent.toFloat(),
            onValueChange = { onVolumeChange(it.toInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = EchoColors.Foreground,
                activeTrackColor = EchoColors.Foreground,
                inactiveTrackColor = EchoColors.PlaceholderBg,
            ),
        )
    }
}
