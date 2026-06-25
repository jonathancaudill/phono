package com.lightphone.spotify.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Scrub UI state — mutated from pointerInput, read only by scrub visuals. */
internal class ScrubController {
    var overlayOpen by mutableStateOf(false)
    var selection by mutableStateOf<ScrubSelectionState?>(null)
    var alphaSelection by mutableStateOf<AlphaSection?>(null)
}
