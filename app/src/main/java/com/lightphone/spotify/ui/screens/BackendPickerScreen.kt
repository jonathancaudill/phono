package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.lightphone.spotify.R
import com.lightphone.spotify.data.backend.BackendChoice
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

/**
 * First-launch backend selection. A phono install is bound to one backend
 * (Spotify or TIDAL); switching means signing out and re-picking. This screen is
 * intentionally standalone (no [com.lightphone.spotify.ui.AppViewModel]) because
 * the controller is backend-specific and is not built until a choice exists.
 */
@Composable
fun BackendPickerScreen(onPicked: (BackendChoice) -> Unit) {
    val colors = LightThemeTokens.colors
    PhonoScreenShell(
        title = "Choose a service",
        hideBackButton = true,
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .lightClickable { onPicked(BackendChoice.TIDAL) },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.logo_tidal),
                contentDescription = "TIDAL",
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .padding(horizontal = legacyNToGridDp(8)),
                contentScale = ContentScale.FillWidth,
                colorFilter = ColorFilter.tint(colors.content),
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .lightClickable { onPicked(BackendChoice.SPOTIFY) },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.logo_spotify),
                contentDescription = "Spotify",
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .padding(horizontal = legacyNToGridDp(8)),
                contentScale = ContentScale.FillWidth,
                colorFilter = ColorFilter.tint(colors.content),
            )
        }
    }
}
