package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.defaultKeyboardOptions
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun SearchScreen(
    vm: AppViewModel,
    onOpenEditor: (String) -> Unit,
) {
    val search by vm.search.collectAsState()

    PhonoScreenShell(
        title = "Search",
        hideBackButton = true,
        rightIconVisible = false,
        horizontalPadding = legacyNToGridDp(20),
        modifier = Modifier.fillMaxSize(),
    ) {
        LightTextField(
            label = "Search:",
            value = search.query,
            placeholder = "Search for something!",
            onClick = { onOpenEditor(search.query) },
            underlineWidthFraction = 1f,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Full-screen search entry with embedded LP3 keyboard (LightOS TextField + TextInputEditor pattern). */
@Composable
fun SearchInputScreen(
    initialQuery: String,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
) {
    val textState = rememberTextFieldState(initialQuery)
    val keyboardOptionsFlow = remember { MutableStateFlow(defaultKeyboardOptions()) }

    LightTextInputEditor(
        title = "Search",
        state = textState,
        keyboardOptionsFlow = keyboardOptionsFlow,
        onSubmit = { text ->
            val query = text.toString().trim()
            if (query.isNotBlank()) onSubmit(query)
        },
        onBack = onBack,
        submitIcon = LightIcons.SEARCH,
        submitLabel = "SEARCH",
        editorKey = initialQuery,
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    )
}
