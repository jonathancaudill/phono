package com.lightphone.spotify.ui.theme

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * Density-normalized scaling, mirroring the LightOS template's `n()` helper
 * (utils/scaling.ts). The reference designs are calibrated so that a value
 * renders at a constant PHYSICAL pixel size (`size * TARGET_DENSITY`) regardless
 * of device density. React Native achieves this with
 * `size * (TARGET_DENSITY / PixelRatio.get())`; here `PixelRatio.get()` maps to
 * Android's `DisplayMetrics.density`.
 *
 * Returning a [Dp] is correct because Compose multiplies dp by the same density
 * at layout time, so the resulting physical size collapses to `size *
 * TARGET_DENSITY` — identical to the reference.
 */
private const val TARGET_DENSITY = 2.55f

private val DENSITY_NORMALIZATION: Float =
    TARGET_DENSITY / Resources.getSystem().displayMetrics.density

/** Scale a design value to a density-normalized [Dp]. */
fun n(size: Number): Dp = (size.toFloat() * DENSITY_NORMALIZATION).dp

/**
 * Font-size counterpart to [n]. Converts through the current density so the text
 * lands at the same physical pixel size as the reference and is unaffected by the
 * user's font-scale setting (matching React Native's `allowFontScaling={false}`).
 */
@Composable
@ReadOnlyComposable
fun nSp(size: Number): TextUnit = with(LocalDensity.current) { n(size).toSp() }
