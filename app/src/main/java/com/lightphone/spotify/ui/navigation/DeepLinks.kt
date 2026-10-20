package com.lightphone.spotify.ui.navigation

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Links from other apps into this one, so a record shop app can open a search or an album here directly:
 *
 *     phono://search?q=Boards+of+Canada+Geogaddi     search results for the text
 *     phono://album/<spotify album id>?title=Geogaddi  that album's page
 *
 * The activity parses the intent and parks the destination here; the shell opens it as soon as it is on screen,
 * which may be later than the intent when the app first has to sign in or finish loading.
 */
object DeepLinks {
    private val _pending = MutableStateFlow<OverlayDestination?>(null)
    val pending: StateFlow<OverlayDestination?> = _pending.asStateFlow()

    /** Reads [intent]; anything that is not one of the links above is ignored. */
    fun handle(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW || uri.scheme != "phono") return
        val title = uri.getQueryParameter("title")?.trim().orEmpty()
        val artist = uri.getQueryParameter("artist")?.trim().orEmpty()
        if (uri.host == "album" && uri.pathSegments.isEmpty() && title.isNotEmpty()) {
            _lookup.value = AlbumLookup(artist, title)
            return
        }
        parse(uri)?.let { _pending.value = it }
    }

    /** An album named by artist and title, resolved by a search once the app is signed in. */
    data class AlbumLookup(val artist: String, val title: String)

    private val _lookup = MutableStateFlow<AlbumLookup?>(null)
    val lookup: StateFlow<AlbumLookup?> = _lookup.asStateFlow()

    fun consumeLookup() {
        _lookup.value = null
    }

    internal fun parse(uri: Uri): OverlayDestination? = when (uri.host) {
        "search" -> uri.getQueryParameter("q")?.trim()?.takeIf { it.isNotEmpty() }?.let { OverlayDestination.SearchResults(it) }
        "album" -> uri.pathSegments.firstOrNull()?.takeIf { it.isNotEmpty() }?.let {
            OverlayDestination.Album(it, uri.getQueryParameter("title").orEmpty())
        }
        else -> null
    }

    /** Called once the destination has been opened. */
    fun consume() {
        _pending.value = null
    }
}
