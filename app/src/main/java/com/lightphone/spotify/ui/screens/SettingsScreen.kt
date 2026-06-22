package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.lightphone.spotify.ffi.NormalizationType
import com.lightphone.spotify.ffi.StreamingQuality
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.EchoContentContainer
import com.lightphone.spotify.ui.components.EchoSectionLabel
import com.lightphone.spotify.ui.components.EchoStyledButton
import com.lightphone.spotify.ui.components.EchoToggleSwitch
import com.lightphone.spotify.ui.components.EchoVolumeSlider
import com.lightphone.spotify.ui.theme.EchoColors

@Composable
fun SettingsScreen(
    vm: AppViewModel,
    onLogout: () -> Unit,
) {
    val settings by vm.settings.collectAsState()
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    if (showClearCacheConfirm) {
        EchoConfirmDialog(
            title = "Clear audio cache",
            message = "Delete downloaded audio cache files? Credentials are kept.",
            confirmText = "Clear",
            onConfirm = {
                vm.clearAudioCache()
                showClearCacheConfirm = false
            },
            onDismiss = { showClearCacheConfirm = false },
        )
    }

    if (showLogoutConfirm) {
        EchoConfirmDialog(
            title = "Logout",
            message = "Are you sure you want to logout?",
            confirmText = "Logout",
            onConfirm = {
                showLogoutConfirm = false
                onLogout()
            },
            onDismiss = { showLogoutConfirm = false },
        )
    }

    EchoContentContainer(
        title = "Settings",
        rightIconVisible = false,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "Spotify for Light Phone",
                style = MaterialTheme.typography.bodyMedium,
                color = EchoColors.Placeholder,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            EchoSectionLabel("Playback")
            EchoVolumeSlider(
                volumePercent = settings.volumePercent,
                onVolumeChange = vm::setVolumePercent,
            )
            Spacer(Modifier.height(20.dp))
            EchoToggleSwitch(
                label = "Gapless playback",
                checked = settings.gaplessEnabled,
                onCheckedChange = vm::setGaplessEnabled,
            )
            EchoToggleSwitch(
                label = "Normalize volume",
                checked = settings.normalizationEnabled,
                onCheckedChange = vm::setNormalizationEnabled,
            )
            if (settings.normalizationEnabled) {
                NormalizationTypePicker(
                    selected = settings.normalizationType,
                    onSelect = vm::setNormalizationType,
                )
            }

            EchoSectionLabel("Audio quality")
            StreamingQualityPicker(
                selected = settings.streamingQuality,
                onSelect = vm::setStreamingQuality,
            )
            Text(
                "Changes apply immediately during playback.",
                style = MaterialTheme.typography.bodyMedium,
                color = EchoColors.Placeholder,
                modifier = Modifier.padding(top = 8.dp),
            )

            EchoSectionLabel("Storage")
            EchoStyledButton(
                text = "Clear audio cache",
                onClick = { showClearCacheConfirm = true },
            )

            EchoSectionLabel("Advanced")
            EchoStyledButton(
                text = if (settings.showAdvanced) "Hide proxy settings" else "Show proxy settings",
                onClick = vm::toggleAdvancedSettings,
            )
            if (settings.showAdvanced) {
                Text(
                    "HTTP proxy (optional)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EchoColors.Placeholder,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                BasicTextField(
                    value = settings.proxy,
                    onValueChange = vm::setProxy,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = EchoColors.Foreground),
                    cursorBrush = SolidColor(EchoColors.Foreground),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    decorationBox = { inner ->
                        if (settings.proxy.isEmpty()) {
                            Text(
                                "http://host:port",
                                style = MaterialTheme.typography.bodyLarge,
                                color = EchoColors.InactiveTab,
                            )
                        }
                        inner()
                    },
                )
                Text(
                    "Reconnects when changed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EchoColors.Placeholder,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            EchoSectionLabel("Account")
            EchoStyledButton(
                text = "Logout",
                onClick = { showLogoutConfirm = true },
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun StreamingQualityPicker(
    selected: StreamingQuality,
    onSelect: (StreamingQuality) -> Unit,
) {
    val options = listOf(
        StreamingQuality.LOW to "Low (96 kbps)",
        StreamingQuality.NORMAL to "Normal (160 kbps)",
        StreamingQuality.HIGH to "High (320 kbps)",
    )
    options.forEach { (quality, label) ->
        EchoStyledButton(
            text = if (quality == selected) "• $label" else label,
            onClick = { onSelect(quality) },
        )
    }
}

@Composable
private fun NormalizationTypePicker(
    selected: NormalizationType,
    onSelect: (NormalizationType) -> Unit,
) {
    val options = listOf(
        NormalizationType.AUTO to "Auto",
        NormalizationType.TRACK to "Track",
        NormalizationType.ALBUM to "Album",
    )
    options.forEach { (type, label) ->
        EchoStyledButton(
            text = if (type == selected) "• $label" else label,
            onClick = { onSelect(type) },
        )
    }
}

@Composable
private fun EchoConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EchoColors.Background,
        titleContentColor = EchoColors.Foreground,
        textContentColor = EchoColors.Placeholder,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = EchoColors.Foreground)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = EchoColors.Placeholder)
            }
        },
    )
}
