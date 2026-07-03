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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import com.lightphone.spotify.ffi.NormalizationType
import com.lightphone.spotify.ffi.StreamingQuality
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

@Composable
fun SettingsScreen(
    vm: AppViewModel,
    onLogout: () -> Unit,
) {
    val settings by vm.settings.collectAsState()
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }

    confirm?.let { request ->
        PhonoConfirmScreen(
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

    PhonoScreenShell(
        title = "Settings",
        hideBackButton = true,
        rightIconVisible = false,
        modifier = Modifier.fillMaxSize(),
    ) {
        LightScrollView(modifier = Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth()) {
                SectionLabel("Playback")
                SettingsToggleRow("Gapless playback", settings.gaplessEnabled, vm::setGaplessEnabled)
                SettingsToggleRow("Normalize volume", settings.normalizationEnabled, vm::setNormalizationEnabled)
                if (settings.normalizationEnabled) {
                    Spacer(Modifier.height(legacyNToGridDp(8)))
                    NormalizationOptions(settings.normalizationType, vm::setNormalizationType)
                }

                SectionLabel("Audio quality")
                StreamingQualityOptions(settings.streamingQuality, vm::setStreamingQuality)

                SectionLabel("Storage")
                SettingsActionRow("Clear Cache") {
                    confirm = ConfirmRequest(
                        title = "Clear Cache",
                        message = "Delete downloaded audio cache files? Credentials are kept.",
                        confirmText = "Clear",
                        onConfirm = { vm.clearAudioCache() },
                    )
                }

                SectionLabel("Advanced")
                SettingsActionRow(
                    text = if (settings.showAdvanced) "Hide proxy settings" else "Show proxy settings",
                    onClick = vm::toggleAdvancedSettings,
                )
                if (settings.showAdvanced) {
                    Spacer(Modifier.height(legacyNToGridDp(12)))
                    ProxyField(settings.proxy, vm::setProxy)
                }

                SectionLabel("Account")
                SettingsActionRow("Logout") {
                    confirm = ConfirmRequest(
                        title = "Logout",
                        message = "Are you sure you want to logout?",
                        confirmText = "Logout",
                        onConfirm = onLogout,
                    )
                }
                Spacer(Modifier.height(legacyNToGridDp(40)))
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
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        color = PhonoSemanticColors.Placeholder,
        modifier = Modifier.padding(top = 1.5f.gridUnitsAsDp(), bottom = legacyNToGridDp(8)),
    )
}

@Composable
private fun SettingsActionRow(
    text: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        underline = selected,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = legacyNToGridDp(8)),
    )
}

@Composable
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable { onCheckedChange(!checked) }
            .padding(vertical = legacyNToGridDp(8)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightIcon(
            icon = if (checked) LightIcons.TOGGLE_ON else LightIcons.TOGGLE_OFF,
            modifier = Modifier.padding(end = legacyNToGridDp(10)),
            contentDescription = null,
        )
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            modifier = Modifier.weight(1f),
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
        SettingsActionRow(text = label, selected = quality == selected, onClick = { onSelect(quality) })
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
        SettingsActionRow(text = label, selected = type == selected, onClick = { onSelect(type) })
    }
}

@Composable
private fun ProxyField(value: String, onChange: (String) -> Unit) {
    val colors = LightThemeTokens.colors
    val typography = LightThemeTokens.typography
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(bottom = legacyNToGridDp(6)),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = typography.copy.copy(color = colors.content),
                cursorBrush = SolidColor(colors.content),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        LightText(
                            text = "http://host:port",
                            variant = LightTextVariant.Copy,
                            color = PhonoSemanticColors.Placeholder,
                        )
                    }
                    inner()
                },
            )
        }
        Box(Modifier.fillMaxWidth().height(legacyNToGridDp(1)).background(colors.content))
    }
}

/** Full-screen destructive confirmation, ported from phono's confirm screen. */
@Composable
fun PhonoConfirmScreen(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    PhonoScreenShell(
        title = title,
        hideBackButton = false,
        onBack = onCancel,
        rightIconVisible = false,
        modifier = Modifier.fillMaxSize(),
    ) {
        LightText(
            text = message,
            variant = LightTextVariant.Paragraph,
            modifier = Modifier.padding(top = legacyNToGridDp(10)),
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LightText(
                text = confirmText.uppercase(),
                variant = LightTextVariant.Subtitle,
                align = TextAlign.Center,
                modifier = Modifier
                    .lightClickable(onClick = onConfirm)
                    .padding(vertical = legacyNToGridDp(15), horizontal = legacyNToGridDp(30)),
            )
        }
    }
}
