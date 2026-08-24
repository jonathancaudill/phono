package com.lightphone.spotify.playback.backend

/**
 * One user-initiated reorder. [uri] is the identity; [indexHint] is the
 * sublist index at click time, used only when it still points at [uri]
 * (duplicate-URI rows). Backends resolve against live state so a stale hint
 * cannot move the wrong track.
 */
data class QueueMoveOp(
    val uri: String,
    val indexHint: Int,
    val section: QueueMoveSection,
    val up: Boolean,
)

enum class QueueMoveSection {
    MANUAL,
    CONTEXT,
}

object QueueMoveIndex {
    /**
     * Map a click onto a live sublist. Prefer [hint] when it still names [uri];
     * otherwise find [uri]. `null` means the track has left this sublist.
     */
    fun resolve(currentUris: List<String>, uri: String, hint: Int): Int? {
        if (hint in currentUris.indices && currentUris[hint] == uri) return hint
        val idx = currentUris.indexOf(uri)
        return idx.takeIf { it >= 0 }
    }
}
