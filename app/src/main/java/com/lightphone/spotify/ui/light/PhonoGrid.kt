package com.lightphone.spotify.ui.light

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * LP3-calibrated bridge from legacy template `n()` design pixels to Light grid units.
 * On 1080×1240 LP3 (~423dp wide): 1 grid unit ≈ 15.7dp ≈ n(15.7).
 */
private const val LEGACY_N_TO_GRID = 1f / 15.7f

@Composable
fun legacyNToGridDp(legacyN: Int): Dp = (legacyN * LEGACY_N_TO_GRID).gridUnitsAsDp()

@Composable
fun legacyNToGridDp(legacyN: Float): Dp = (legacyN * LEGACY_N_TO_GRID).gridUnitsAsDp()
