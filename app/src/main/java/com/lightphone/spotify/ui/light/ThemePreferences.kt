package com.lightphone.spotify.ui.light

import android.content.Context
import com.thelightphone.sdk.ui.LightThemeController

/** UI-only dark/light preference (not part of playback settings). */
class ThemePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isDarkTheme(): Boolean = prefs.getBoolean(KEY_DARK_THEME, true)

    fun setDarkTheme(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DARK_THEME, enabled).apply()
        applyToController(enabled)
    }

    fun applyToController() {
        applyToController(isDarkTheme())
    }

    private fun applyToController(dark: Boolean) {
        if (dark) {
            LightThemeController.setDarkTheme()
        } else {
            LightThemeController.setLightTheme()
        }
    }

    companion object {
        private const val PREFS_NAME = "phono_theme"
        private const val KEY_DARK_THEME = "dark_theme"
    }
}
