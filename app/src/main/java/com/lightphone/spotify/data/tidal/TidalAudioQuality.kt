package com.lightphone.spotify.data.tidal

/**
 * TIDAL stream tiers (private `playbackinfopostpaywall` `audioquality` values).
 * Labels match the official client’s Low / High / Max language rather than
 * Spotify’s 96/160/320 kbps ladder.
 */
enum class TidalAudioQuality(
    /** Value sent as `audioquality=` on playbackinfo. */
    val apiValue: String,
    val label: String,
) {
    /** AAC ~96 kbps. */
    EXTRA_LOW("LOW", "Extra low (96 kbps)"),

    /** AAC ~320 kbps. */
    LOW("HIGH", "Low (320 kbps)"),

    /** FLAC 16-bit / 44.1 kHz (CD). */
    HIGH("LOSSLESS", "High (16-bit / 44.1 kHz)"),

    /** FLAC up to 24-bit / 192 kHz (HiRes / Max). */
    MAX("HI_RES_LOSSLESS", "Max (up to 24-bit / 192 kHz)");

    companion object {
        val DEFAULT = HIGH

        fun fromApiValue(value: String?): TidalAudioQuality =
            entries.firstOrNull { it.apiValue.equals(value, ignoreCase = true) }
                ?: entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: DEFAULT
    }
}
