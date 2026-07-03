package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

@Composable
fun SearchScreen(
    vm: AppViewModel,
    onSubmit: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val colors = LightThemeTokens.colors
    val typography = LightThemeTokens.typography

    PhonoScreenShell(
        title = "Search",
        hideBackButton = true,
        rightIcon = Icons.Default.Check,
        onRightIconClick = { if (query.isNotBlank()) onSubmit(query) },
        rightIconVisible = query.isNotEmpty(),
        horizontalPadding = legacyNToGridDp(20),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        vm.updateSearchQuery(it)
                    },
                    textStyle = typography.copy.copy(color = colors.content),
                    cursorBrush = SolidColor(colors.content),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onSubmit(query) }),
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = legacyNToGridDp(2), bottom = legacyNToGridDp(6)),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) {
                                LightText(
                                    text = "Search for something!",
                                    variant = LightTextVariant.Copy,
                                    color = PhonoSemanticColors.Placeholder,
                                )
                            }
                            inner()
                        }
                    },
                )
                if (query.isNotEmpty()) {
                    Box(
                        Modifier
                            .padding(legacyNToGridDp(5))
                            .lightClickable { query = "" },
                    ) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = "Clear",
                            tint = colors.content,
                            modifier = Modifier.size(legacyNToGridDp(24)),
                        )
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(legacyNToGridDp(1))
                    .background(colors.content),
            )
        }
    }
}
