package com.lightphone.spotify.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavController

private const val OVERLAY_NAV_DEBOUNCE_MS = 400L

/**
 * Central guard for modal overlay destinations (Playing, album/artist detail, search results).
 * Uses [launchSingleTop] so an destination already on the back stack is reused, and debounces
 * duplicate navigations to the same route while the first is still in flight.
 */
class OverlayNavigator(private val navController: NavController) {
    private var lastRoute: String? = null
    private var lastNavAtMs: Long = 0L

    fun navigate(route: String) {
        val now = System.currentTimeMillis()
        if (route == lastRoute && now - lastNavAtMs < OVERLAY_NAV_DEBOUNCE_MS) return
        lastRoute = route
        lastNavAtMs = now
        navController.navigate(route) {
            launchSingleTop = true
        }
    }
}

@Composable
fun rememberOverlayNavigator(navController: NavController): OverlayNavigator =
    remember(navController) { OverlayNavigator(navController) }
