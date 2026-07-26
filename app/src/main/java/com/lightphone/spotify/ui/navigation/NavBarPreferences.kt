package com.lightphone.spotify.ui.navigation

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One nav tab entry: its stable [PhonoTab.route] plus whether the user shows it. */
data class NavTabPref(val route: String, val enabled: Boolean)

/**
 * Persisted show/hide + order of the bottom navigation tabs. UI-only and
 * non-secret, so plain SharedPreferences like [com.lightphone.spotify.ui.light.ThemePreferences].
 *
 * The order is also published as a process-wide [StateFlow] so the tab bar (in
 * [PhonoShell]) and the editor (in SettingsScreen) observe one source of truth
 * without threading it through a ViewModel. Construct once (e.g. from
 * App.onCreate) to prime the flow from disk; construct again anywhere to call
 * [setOrder].
 */
class NavBarPreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        synchronized(lock) {
            if (!loaded) {
                _order.value = decode(prefs.getString(KEY_ORDER, null))
                loaded = true
            }
        }
    }

    /** Persist a new order/visibility. [PhonoTab.Settings] is always forced visible. */
    fun setOrder(list: List<NavTabPref>) {
        val safe = list.map { if (it.route in LOCKED) it.copy(enabled = true) else it }
        prefs.edit().putString(KEY_ORDER, encode(safe)).apply()
        _order.value = safe
    }

    companion object {
        private const val PREFS_NAME = "phono_navbar"
        private const val KEY_ORDER = "order"

        /** Settings can never be hidden, so a user can't strand themselves. */
        val LOCKED = setOf(PhonoTab.Settings.route)

        private val lock = Any()
        @Volatile
        private var loaded = false

        /** Canonical full order (all tabs) — first run, and the anchor for new tabs. */
        private val DEFAULT: List<NavTabPref> =
            phonoTabs(includeDownloads = true).map { NavTabPref(it.route, true) }

        private val _order = MutableStateFlow(DEFAULT)
        val order: StateFlow<List<NavTabPref>> = _order.asStateFlow()

        /**
         * Tabs to actually show, intersected with the tabs this build offers
         * (e.g. [PhonoTab.Downloads] only when supported). Keeps the user's
         * order, drops hidden/unavailable tabs, and never returns empty
         * (Settings always survives).
         */
        fun visibleTabs(available: List<PhonoTab>): List<PhonoTab> {
            val byRoute = available.associateBy { it.route }
            val shown = _order.value.filter { it.enabled }.mapNotNull { byRoute[it.route] }
            return shown.ifEmpty { available.filter { it.route in LOCKED } }
        }

        private fun encode(list: List<NavTabPref>): String =
            list.joinToString(",") { "${it.route}:${if (it.enabled) 1 else 0}" }

        /** Decode the stored string; slot any newly added default tab at its natural spot. */
        private fun decode(raw: String?): List<NavTabPref> {
            if (raw.isNullOrBlank()) return DEFAULT
            val stored = raw.split(",").mapNotNull { entry ->
                val parts = entry.split(":")
                if (parts.size == 2 && DEFAULT.any { it.route == parts[0] }) {
                    NavTabPref(parts[0], parts[1] == "1")
                } else {
                    null
                }
            }.toMutableList()
            DEFAULT.forEachIndexed { i, d ->
                if (stored.none { it.route == d.route }) {
                    val prev = DEFAULT.subList(0, i)
                        .lastOrNull { pd -> stored.any { it.route == pd.route } }?.route
                    val at = if (prev == null) 0 else stored.indexOfFirst { it.route == prev } + 1
                    stored.add(at, d)
                }
            }
            return stored
        }
    }
}
