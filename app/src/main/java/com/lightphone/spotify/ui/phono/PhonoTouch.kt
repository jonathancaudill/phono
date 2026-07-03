package com.lightphone.spotify.ui.phono

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/** Full-screen scrim that absorbs touches so they do not reach content beneath. */
fun Modifier.consumeScrimTouches(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown().consume()
    }
}
