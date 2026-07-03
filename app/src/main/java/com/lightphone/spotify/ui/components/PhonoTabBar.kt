package com.lightphone.spotify.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.navigation.PhonoTab
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp

private const val TAB_ICON_SIZE_UNITS = 2.35f

/** Bottom bar footprint: 0.5 grid top margin + 3.5 grid bar height. */
val PhonoNavbarBarHeight: Dp @Composable get() = 4f.gridUnitsAsDp()

val PhonoNavbarOfflineStripHeight: Dp @Composable get() = legacyNToGridDp(18)

@Composable
fun phonoNavbarTotalHeight(showOfflineStrip: Boolean): Dp =
    PhonoNavbarBarHeight + if (showOfflineStrip) PhonoNavbarOfflineStripHeight else 0.dp

@Composable
fun PhonoTabBar(
    tabs: List<PhonoTab>,
    currentTab: PhonoTab,
    onTabSelected: (PhonoTab) -> Unit,
    statusMessage: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LightThemeTokens.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.background),
    ) {
        LightBottomBar(
            items = tabs.map { tab ->
                val active = tab == currentTab
                val tint = if (active) colors.content else PhonoSemanticColors.InactiveTab
                when {
                    tab.lightIcon != null -> LightBarButton.LightIcon(
                        icon = tab.lightIcon,
                        onClick = { onTabSelected(tab) },
                        contentDescription = tab.label,
                        sizeUnits = TAB_ICON_SIZE_UNITS,
                        tint = tint,
                    )
                    tab.icon != null -> LightBarButton.Icon(
                        painter = rememberVectorPainter(tab.icon),
                        onClick = { onTabSelected(tab) },
                        contentDescription = tab.label,
                        sizeUnits = TAB_ICON_SIZE_UNITS,
                        tint = tint,
                    )
                    else -> error("Tab ${tab.label} has no icon")
                }
            },
        )
        if (statusMessage != null) {
            OfflineStrip(statusMessage)
        }
    }
}

@Composable
private fun OfflineStrip(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(legacyNToGridDp(18))
            .background(PhonoSemanticColors.OfflineStripBg),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = message,
            variant = LightTextVariant.Micro,
            color = PhonoSemanticColors.OfflineStripFg,
        )
    }
}
