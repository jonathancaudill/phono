package com.lightphone.spotify.data.tidal

/**
 * Tidal has no social profiles. Official client labels strangers as `"user"`.
 * Editorial/system playlists use owner id `"0"`.
 */
object TidalPlaylistOwners {
    fun label(ownerId: String, me: String?): String = when {
        ownerId == "0" -> "TIDAL"
        !me.isNullOrBlank() && ownerId == me -> "you"
        else -> "user"
    }

    /**
     * Prefer a rare non-placeholder [creatorName] from the API; otherwise [label].
     */
    fun resolve(ownerId: String, creatorName: String?, me: String?): String {
        if (ownerId == "0") return "TIDAL"
        if (!me.isNullOrBlank() && ownerId == me) return "you"
        val usable = creatorName?.trim()?.takeIf { it.isNotEmpty() }
            ?.takeUnless { it.equals("user", ignoreCase = true) }
            ?.takeUnless { it == ownerId }
            ?.takeUnless { it.all(Char::isDigit) }
        if (usable != null) return usable
        return "user"
    }

    /** UI safety net when Room still has blank / numeric owner_name. */
    fun displayForUi(ownerId: String, ownerName: String, me: String?): String {
        val name = ownerName.trim()
        if (name.isNotEmpty() && name != ownerId && !name.all(Char::isDigit)) return name
        return label(ownerId, me)
    }
}
