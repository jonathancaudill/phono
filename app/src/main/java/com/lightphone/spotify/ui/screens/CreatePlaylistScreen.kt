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
import androidx.compose.ui.text.TextStyle
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.PhonoContentContainer
import com.lightphone.spotify.ui.components.PhonoStyledButton
import com.lightphone.spotify.ui.components.PhonoToggleSwitch
import com.lightphone.spotify.ui.components.StyledText
import com.lightphone.spotify.ui.theme.PhonoColors
import com.lightphone.spotify.ui.theme.PublicSans
import com.lightphone.spotify.ui.theme.n
import com.lightphone.spotify.ui.theme.nSp

@Composable
fun CreatePlaylistScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onCreated: (String, String) -> Unit,
) {
    val state by vm.createPlaylist.collectAsState()
    var name by remember { mutableStateOf("") }
    var isPublic by remember { mutableStateOf(false) }

    PhonoContentContainer(
        title = "New playlist",
        hideBackButton = false,
        onBack = onBack,
        rightIconVisible = false,
        horizontalPadding = n(37),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            StyledText("Name", size = 14, color = PhonoColors.Placeholder)
            Spacer(Modifier.height(n(8)))
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                textStyle = TextStyle(
                    color = PhonoColors.Foreground,
                    fontSize = nSp(22),
                    fontFamily = PublicSans,
                ),
                cursorBrush = SolidColor(PhonoColors.Foreground),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(n(20)))
            PhonoToggleSwitch("Public playlist", isPublic, onCheckedChange = { isPublic = it })
            if (state.error != null) {
                Spacer(Modifier.height(n(12)))
                StyledText(state.error!!, size = 14, color = PhonoColors.Error)
            }
            Spacer(Modifier.height(n(24)))
            PhonoStyledButton(
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
