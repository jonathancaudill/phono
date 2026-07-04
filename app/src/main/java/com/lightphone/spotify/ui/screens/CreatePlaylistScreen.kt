package com.lightphone.spotify.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.defaultKeyboardOptions
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun CreatePlaylistScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onCreated: (String, String) -> Unit,
) {
    val state by vm.createPlaylist.collectAsState()
    var name by remember { mutableStateOf("") }
    var isPublic by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(false) }
    var editorSession by remember { mutableIntStateOf(0) }

    BackHandler {
        if (editingName) {
            editingName = false
        } else {
            onBack()
        }
    }

    if (editingName) {
        key(editorSession) {
            val textState = rememberTextFieldState(name)
            val keyboardOptionsFlow = remember { MutableStateFlow(defaultKeyboardOptions()) }
            LightTextInputEditor(
                title = "Name",
                state = textState,
                keyboardOptionsFlow = keyboardOptionsFlow,
                onSubmit = { text ->
                    name = text.toString().trim()
                    editingName = false
                },
                onBack = { editingName = false },
                submitIcon = LightIcons.ACCEPT,
                submitLabel = "DONE",
                editorKey = editorSession,
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            )
        }
        return
    }

    PhonoScreenShell(
        title = "New playlist",
        hideBackButton = false,
        onBack = onBack,
        rightIcon = Icons.Default.Check,
        onRightIconClick = {
            val trimmed = name.trim()
            if (trimmed.isBlank() || state.creating) return@PhonoScreenShell
            vm.createPlaylist(trimmed, isPublic) { id, title ->
                onCreated(id, title)
            }
        },
        rightIconVisible = name.isNotBlank(),
        rightLoading = state.creating,
        horizontalPadding = legacyNToGridDp(37),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            LightTextField(
                label = "Name:",
                value = name,
                placeholder = "Playlist name",
                onClick = {
                    editorSession++
                    editingName = true
                },
                underlineWidthFraction = 1f,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(legacyNToGridDp(20)))
            PublicPlaylistToggle(
                checked = isPublic,
                onCheckedChange = { isPublic = it },
            )
            if (state.error != null) {
                Spacer(Modifier.height(legacyNToGridDp(12)))
                LightText(
                    text = state.error!!,
                    variant = LightTextVariant.Detail,
                    color = PhonoSemanticColors.Error,
                )
            }
        }
    }
}

@Composable
private fun PublicPlaylistToggle(
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
            // LightIcons names are inverted vs the artwork (knob-left is labeled ON).
            icon = if (checked) LightIcons.TOGGLE_OFF else LightIcons.TOGGLE_ON,
            modifier = Modifier.padding(end = legacyNToGridDp(10)),
            contentDescription = null,
        )
        LightText(
            text = "Public playlist",
            variant = LightTextVariant.Copy,
            modifier = Modifier.weight(1f),
        )
    }
}
