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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.MonoContentContainer
import com.lightphone.spotify.ui.components.StyledText
import com.lightphone.spotify.ui.components.tap
import com.lightphone.spotify.ui.theme.MonoColors
import com.lightphone.spotify.ui.theme.PublicSans
import com.lightphone.spotify.ui.theme.n
import com.lightphone.spotify.ui.theme.nSp

@Composable
fun SearchScreen(
    vm: AppViewModel,
    onSubmit: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }

    MonoContentContainer(
        title = "Search",
        hideBackButton = true,
        rightIcon = Icons.Default.Check,
        onRightIconClick = { if (query.isNotBlank()) onSubmit(query) },
        rightIconVisible = query.isNotEmpty(),
        horizontalPadding = n(20),
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
                    textStyle = TextStyle(
                        color = MonoColors.Foreground,
                        fontSize = nSp(24),
                        fontFamily = PublicSans,
                    ),
                    cursorBrush = SolidColor(MonoColors.Foreground),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onSubmit(query) }),
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = n(2), bottom = n(6)),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) {
                                StyledText(
                                    "Search for something!",
                                    size = 24,
                                    color = MonoColors.Placeholder,
                                )
                            }
                            inner()
                        }
                    },
                )
                if (query.isNotEmpty()) {
                    Box(
                        Modifier
                            .padding(n(5))
                            .tap { query = "" },
                    ) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = "Clear",
                            tint = MonoColors.Foreground,
                            modifier = Modifier.size(n(24)),
                        )
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(n(1))
                    .background(MonoColors.Foreground),
            )
        }
    }
}
