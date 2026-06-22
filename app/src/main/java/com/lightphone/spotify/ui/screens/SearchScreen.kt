package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.EchoContentContainer
import com.lightphone.spotify.ui.theme.EchoColors

@Composable
fun SearchScreen(
    vm: AppViewModel,
    onSubmit: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }

    EchoContentContainer(
        title = "Search",
        rightIcon = Icons.Default.Check,
        onRightIconClick = { if (query.isNotBlank()) onSubmit(query) },
        rightIconVisible = query.isNotEmpty(),
        modifier = Modifier.fillMaxSize(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(width = 0.dp, color = EchoColors.Foreground)
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = query,
                onValueChange = {
                    query = it
                    vm.updateSearchQuery(it)
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = EchoColors.Foreground,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * 1.1f,
                ),
                cursorBrush = SolidColor(EchoColors.Foreground),
                singleLine = true,
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                "Search for something!",
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize * 1.1f),
                                color = EchoColors.Placeholder,
                            )
                        }
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear", tint = EchoColors.Foreground)
                }
            }
        }
    }
}
