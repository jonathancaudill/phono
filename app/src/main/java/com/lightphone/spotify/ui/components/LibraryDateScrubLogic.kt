package com.lightphone.spotify.ui.components

import kotlin.math.roundToInt

internal const val SCRUB_ZONE_FRACTION = 0.65f
internal const val SCRUB_WHEEL_VISIBLE_COUNT = 7
internal const val SCRUB_WHEEL_CENTER_SLOT = 3

internal data class ScrubWheelItem(
    val itemIndex: Int,
    val slotIndex: Int,
)

internal fun scrubZoneBounds(heightPx: Float, zoneFraction: Float = SCRUB_ZONE_FRACTION): Pair<Float, Float> {
    val margin = (1f - zoneFraction) / 2f
    return heightPx * margin to heightPx * (1f - margin)
}

internal fun scrubWheelVisibleItems(
    selectedIndex: Int,
    count: Int,
    windowSize: Int = SCRUB_WHEEL_VISIBLE_COUNT,
    centerSlot: Int = SCRUB_WHEEL_CENTER_SLOT,
): List<ScrubWheelItem> {
    if (count <= 0) return emptyList()
    val halfWindow = (windowSize - 1) / 2
    return buildList {
        for (offset in -halfWindow..halfWindow) {
            val itemIndex = selectedIndex + offset
            if (itemIndex in 0 until count) {
                add(ScrubWheelItem(itemIndex = itemIndex, slotIndex = centerSlot + offset))
            }
        }
    }
}

internal fun indexForVerticalPosition(
    yPx: Float,
    count: Int,
    heightPx: Float,
    zoneFraction: Float? = SCRUB_ZONE_FRACTION,
): Int {
    if (count <= 0) return 0
    if (count == 1) return 0
    val (top, bottom) = if (zoneFraction != null) {
        scrubZoneBounds(heightPx, zoneFraction)
    } else {
        0f to heightPx
    }
    val zoneHeight = bottom - top
    val yInZone = (yPx - top).coerceIn(0f, zoneHeight)
    val fraction = if (zoneHeight > 0f) yInZone / zoneHeight else 0f
    return (fraction * (count - 1)).roundToInt().coerceIn(0, count - 1)
}
