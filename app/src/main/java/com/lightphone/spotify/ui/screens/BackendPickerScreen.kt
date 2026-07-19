package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.lightphone.spotify.data.backend.BackendChoice
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * First-launch backend selection. A phono install is bound to one backend
 * (Spotify or TIDAL); switching means signing out and re-picking. This screen is
 * intentionally standalone (no [com.lightphone.spotify.ui.AppViewModel]) because
 * the controller is backend-specific and is not built until a choice exists.
 *
 * Layout is top-aligned + scrollable: vertical centering on the Light Phone III
 * clipped the second option off-screen.
 */
@Composable
fun BackendPickerScreen(onPicked: (BackendChoice) -> Unit) {
    val colors = LightThemeTokens.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = legacyNToGridDp(22)),
    ) {
        Spacer(Modifier.height(legacyNToGridDp(16)))
        LightText(text = "Choose your service", variant = LightTextVariant.Title)
        LightText(
            text = "One service per install. Sign out to switch.",
            variant = LightTextVariant.Detail,
            color = PhonoSemanticColors.Placeholder,
            modifier = Modifier.padding(top = legacyNToGridDp(6), bottom = legacyNToGridDp(14)),
        )
        LightScrollView(modifier = Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth()) {
                BackendOption(
                    title = "Spotify",
                    subtitle = "Premium · librespot",
                    onClick = { onPicked(BackendChoice.SPOTIFY) },
                )
                Spacer(Modifier.height(legacyNToGridDp(10)))
                BackendOption(
                    title = "TIDAL",
                    subtitle = "Lossless · Media3",
                    onClick = { onPicked(BackendChoice.TIDAL) },
                )
            }
        }
    }
}

@Composable
private fun BackendOption(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, PhonoSemanticColors.Placeholder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = legacyNToGridDp(14), vertical = legacyNToGridDp(12)),
    ) {
        LightText(text = title, variant = LightTextVariant.Copy)
        LightText(
            text = subtitle,
            variant = LightTextVariant.Detail,
            color = PhonoSemanticColors.Placeholder,
        )
    }
}
