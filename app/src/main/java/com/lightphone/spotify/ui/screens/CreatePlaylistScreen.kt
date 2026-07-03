package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.PhonoToggleSwitch
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.lightphone.spotify.ui.phono.PhonoTextButton
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens

@Composable
fun CreatePlaylistScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onCreated: (String, String) -> Unit,
) {
    val state by vm.createPlaylist.collectAsState()
    var name by remember { mutableStateOf("") }
    var isPublic by remember { mutableStateOf(false) }
    val colors = LightThemeTokens.colors
    val typography = LightThemeTokens.typography

    PhonoScreenShell(
        title = "New playlist",
        hideBackButton = false,
        onBack = onBack,
        rightIconVisible = false,
        horizontalPadding = legacyNToGridDp(37),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            LightText(text = "Name", variant = LightTextVariant.Detail, color = PhonoSemanticColors.Placeholder)
            Spacer(Modifier.height(legacyNToGridDp(8)))
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                textStyle = typography.copy.copy(color = colors.content),
                cursorBrush = SolidColor(colors.content),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(legacyNToGridDp(20)))
            PhonoToggleSwitch("Public playlist", isPublic, onCheckedChange = { isPublic = it })
            if (state.error != null) {
                Spacer(Modifier.height(legacyNToGridDp(12)))
                LightText(text = state.error!!, variant = LightTextVariant.Detail, color = PhonoSemanticColors.Error)
            }
            Spacer(Modifier.height(legacyNToGridDp(24)))
            PhonoTextButton(
                text = if (state.creating) "Creating…" else "Create",
                onClick = {
                    vm.createPlaylist(name.trim(), isPublic) { id, title ->
                        onCreated(id, title)
                    }
                },
            )
        }
    }
}
