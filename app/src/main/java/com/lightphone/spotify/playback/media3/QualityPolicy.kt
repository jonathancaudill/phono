package com.lightphone.spotify.playback.media3

import com.lightphone.spotify.playback.NetworkTier

/**
 * Resolve-time quality intent: user preference capped by network tier.
 * Changes are [pending] until the next MediaItem boundary — never mid-decode.
 *
 * [Q] is backend-specific (e.g. [com.lightphone.spotify.data.tidal.TidalAudioQuality]).
 */
class QualityPolicy<Q>(
    initial: Q,
    private val ceilingForTier: (NetworkTier, Q) -> Q,
) {
    @Volatile
    private var user: Q = initial

    @Volatile
    private var pending: Q? = null

    @Volatile
    private var applied: Q = initial

    fun userQuality(): Q = user

    /** Quality currently frozen on the playing / last-resolved item. */
    fun appliedQuality(): Q = applied

    /**
     * Effective quality for a new resolve: pending user change (if any) else
     * current user preference, capped by [tier].
     */
    fun effectiveForResolve(tier: NetworkTier): Q =
        ceilingForTier(tier, pending ?: user)

    /** Persist user intent; does not change [applied] until [commitPending]. */
    fun setUserQuality(quality: Q) {
        user = quality
        pending = quality
    }

    /**
     * Commit pending → applied at MediaItem transition / next resolve window.
     * Returns the quality that should be used going forward.
     */
    fun commitPending(tier: NetworkTier): Q {
        val next = effectiveForResolve(tier)
        applied = next
        pending = null
        return next
    }

    /** Force applied = user (e.g. cold start / playUris) with tier ceiling. */
    fun applyNow(tier: NetworkTier): Q {
        pending = null
        applied = ceilingForTier(tier, user)
        return applied
    }
}

/** Shared ceiling helper: [ladderLowToHigh] ordered from lowest to highest fidelity. */
object QualityCeilings {
    fun <Q> cap(
        tier: NetworkTier,
        requested: Q,
        ladderLowToHigh: List<Q>,
    ): Q {
        if (ladderLowToHigh.isEmpty()) return requested
        val reqIdx = ladderLowToHigh.indexOf(requested).let { if (it < 0) ladderLowToHigh.lastIndex else it }
        val maxIdx = when (tier) {
            NetworkTier.OFFLINE -> reqIdx
            // AAC rungs only on weak cell.
            NetworkTier.POOR -> 1.coerceAtMost(ladderLowToHigh.lastIndex)
            // Allow CD lossless; hold Max for unmetered Wi‑Fi.
            NetworkTier.FAIR, NetworkTier.GOOD_METERED ->
                2.coerceAtMost(ladderLowToHigh.lastIndex)
            NetworkTier.GOOD_UNMETERED -> ladderLowToHigh.lastIndex
        }
        return ladderLowToHigh[reqIdx.coerceAtMost(maxIdx)]
    }
}
