package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.lightphone.spotify.ffi.NormalizationType
import com.lightphone.spotify.ffi.StreamingQuality
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.CustomScrollView
import com.lightphone.spotify.ui.components.MonoContentContainer
import com.lightphone.spotify.ui.components.MonoStyledButton
import com.lightphone.spotify.ui.components.MonoToggleSwitch
import com.lightphone.spotify.ui.components.StyledText
import com.lightphone.spotify.ui.components.tap
import com.lightphone.spotify.ui.theme.MonoColors
import com.lightphone.spotify.ui.theme.PublicSans
import com.lightphone.spotify.ui.theme.n
import com.lightphone.spotify.ui.theme.nSp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: AppViewModel,
    onLogout: () -> Unit,
) {
    val settings by vm.settings.collectAsState()
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }

    confirm?.let { request ->
        MonoConfirmScreen(
            title = request.title,
            message = request.message,
            confirmText = request.confirmText,
            onConfirm = {
                request.onConfirm()
                confirm = null
            },
            onCancel = { confirm = null },
        )
        return
    }

    MonoContentContainer(
        title = "Settings",
        hideBackButton = true,
        rightIconVisible = false,
        horizontalPadding = n(37),
        contentGap = n(0),
        modifier = Modifier.fillMaxSize(),
    ) {
        CustomScrollView(modifier = Modifier.weight(1f)) {
            item("body") {
                Column(Modifier.fillMaxWidth().padding(end = n(20))) {
                    SectionLabel("Playback")
                    VolumeControl(settings.volumePercent, vm::setVolumePercent)
                    Spacer(Modifier.height(n(20)))
                    MonoToggleSwitch("Gapless playback", settings.gaplessEnabled, vm::setGaplessEnabled)
                    MonoToggleSwitch("Normalize volume", settings.normalizationEnabled, vm::setNormalizationEnabled)
                    if (settings.normalizationEnabled) {
                        Spacer(Modifier.height(n(8)))
                        NormalizationOptions(settings.normalizationType, vm::setNormalizationType)
                    }

                    SectionLabel("Audio quality")
                    StreamingQualityOptions(settings.streamingQuality, vm::setStreamingQuality)

                    SectionLabel("Storage")
                    MonoStyledButton(
                        text = "Clear Cache",
                        onClick = {
                            confirm = ConfirmRequest(
                                title = "Clear Cache",
                                message = "Delete downloaded audio cache files? Credentials are kept.",
                                confirmText = "Clear",
                                onConfirm = { vm.clearAudioCache() },
                            )
                        },
                    )

                    SectionLabel("Advanced")
                    MonoStyledButton(
                        text = if (settings.showAdvanced) "Hide proxy settings" else "Show proxy settings",
                        onClick = vm::toggleAdvancedSettings,
                    )
                    if (settings.showAdvanced) {
                        Spacer(Modifier.height(n(12)))
                        ProxyField(settings.proxy, vm::setProxy)
                    }

                    SectionLabel("Account")
                    MonoStyledButton(
                        text = "Logout",
                        onClick = {
                            confirm = ConfirmRequest(
                                title = "Logout",
                                message = "Are you sure you want to logout?",
                                confirmText = "Logout",
                                onConfirm = onLogout,
                            )
                        },
                    )
                    Spacer(Modifier.height(n(40)))
                }
            }
        }
    }
}

private data class ConfirmRequest(
    val title: String,
    val message: String,
    val confirmText: String,
    val onConfirm: () -> Unit,
)

@Composable
private fun SectionLabel(text: String) {
    StyledText(
        text.uppercase(),
        size = 12,
        color = MonoColors.Placeholder,
        modifier = Modifier.padding(top = n(24), bottom = n(12)),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VolumeControl(volumePercent: Int, onVolumeChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StyledText("Volume", size = 22, modifier = Modifier.weight(1f))
            StyledText("$volumePercent%", size = 16, color = MonoColors.Placeholder)
        }
        Slider(
            value = volumePercent.toFloat(),
            onValueChange = { onVolumeChange(it.toInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MonoColors.Foreground,
                activeTrackColor = MonoColors.Foreground,
                inactiveTrackColor = MonoColors.PlaceholderBg,
            ),
        )
    }
}

@Composable
private fun StreamingQualityOptions(selected: StreamingQuality, onSelect: (StreamingQuality) -> Unit) {
    val options = listOf(
        StreamingQuality.LOW to "Low (96 kbps)",
        StreamingQuality.NORMAL to "Normal (160 kbps)",
        StreamingQuality.HIGH to "High (320 kbps)",
    )
    options.forEach { (quality, label) ->
        MonoStyledButton(text = label, selected = quality == selected, onClick = { onSelect(quality) })
        Spacer(Modifier.height(n(6)))
    }
}

@Composable
private fun NormalizationOptions(selected: NormalizationType, onSelect: (NormalizationType) -> Unit) {
    val options = listOf(
        NormalizationType.AUTO to "Auto",
        NormalizationType.TRACK to "Track",
        NormalizationType.ALBUM to "Album",
    )
    options.forEach { (type, label) ->
        MonoStyledButton(text = label, selected = type == selected, onClick = { onSelect(type) })
        Spacer(Modifier.height(n(6)))
    }
}

@Composable
private fun ProxyField(value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(bottom = n(6)),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = TextStyle(color = MonoColors.Foreground, fontSize = nSp(22), fontFamily = PublicSans),
                cursorBrush = SolidColor(MonoColors.Foreground),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        StyledText("http://host:port", size = 22, color = MonoColors.Placeholder)
                    }
                    inner()
                },
            )
        }
        Box(Modifier.fillMaxWidth().height(n(1)).background(MonoColors.Foreground))
    }
}

/** Full-screen destructive confirmation, ported from mono's confirm screen. */
@Composable
fun MonoConfirmScreen(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    MonoContentContainer(
        title = title,
        hideBackButton = false,
        onBack = onCancel,
        rightIconVisible = false,
        modifier = Modifier.fillMaxSize(),
    ) {
        StyledText(message, size = 18, color = MonoColors.Foreground, modifier = Modifier.padding(top = n(10)))
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StyledText(
                confirmText.uppercase(),
                size = 40,
                color = MonoColors.Foreground,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .tap(onClick = onConfirm)
                    .padding(vertical = n(15), horizontal = n(30)),
            )
        }
    }
}
