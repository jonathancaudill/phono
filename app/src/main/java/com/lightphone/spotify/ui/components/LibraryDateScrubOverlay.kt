package com.lightphone.spotify.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.theme.n
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlin.math.abs

/** Visual scrollbar track width (drawn at the screen's right gutter). */
internal val SCRUBBAR_TOUCH_WIDTH: Dp = n(24)
/** Empty margin to the right of list text — matches screen shell horizontal padding. */
internal val SCROLLBAR_SCREEN_GUTTER: Dp = n(20)
internal val SCRUB_YEARS_COLUMN_WIDTH: Dp = n(56)
internal val SCRUB_MONTHS_COLUMN_WIDTH: Dp = n(168)
private val SCRUB_COLUMN_GAP: Dp = n(20)
/** Short tick to the right of each label — finger rests here so text stays readable. */
private val SCRUB_DEADZONE_TICK_WIDTH: Dp = n(36)
private val SCRUB_DEADZONE_TICK_HEIGHT: Dp = n(2)
private val SCRUB_DEADZONE_TICK_GAP: Dp = n(4)

internal enum class ScrubColumn {
    Months,
    Years,
    Scrollbar,
}

internal fun scrubColumnAt(
    xPx: Float,
    totalWidthPx: Float,
    density: Float,
    stripWidthDp: Dp = SCRUBBAR_TOUCH_WIDTH,
): ScrubColumn {
    val scrollbarW = stripWidthDp.value * density
    val yearsW = SCRUB_YEARS_COLUMN_WIDTH.value * density
    val monthsW = SCRUB_MONTHS_COLUMN_WIDTH.value * density
    val yearsLeft = totalWidthPx - scrollbarW - yearsW
    val monthsLeft = yearsLeft - monthsW - (SCRUB_COLUMN_GAP.value * density)
    return when {
        xPx >= totalWidthPx - scrollbarW -> ScrubColumn.Scrollbar
        xPx >= yearsLeft -> ScrubColumn.Years
        xPx >= monthsLeft -> ScrubColumn.Months
        else -> ScrubColumn.Months
    }
}

/** Scrim + labels only — composed separately from the LazyColumn so open/close is instant. */
@Composable
internal fun LibraryAlphaScrubVisuals(
    controller: ScrubController,
    alphaIndex: LibraryAlphaIndex,
    modifier: Modifier = Modifier,
    screenEdgeGutter: Dp = SCROLLBAR_SCREEN_GUTTER,
    scrimColor: Color? = null,
) {
    if (!controller.overlayOpen || controller.alphaSelection == null) return
    val resolvedScrim = scrimColor ?: LightThemeTokens.colors.background

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(color = resolvedScrim, size = Size(size.width, size.height))
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                }
            },
    ) {
        ScrubLabelColumn(
            labels = alphaIndex.letters.map { if (it == '#') "#" else it.toString() },
            selectedIndex = alphaIndex.letters.indexOf(controller.alphaSelection!!.letter).takeIf { it >= 0 },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .padding(end = screenEdgeGutter)
                .width(SCRUB_YEARS_COLUMN_WIDTH),
        )
    }
}

internal fun initialAlphaScrubSelection(alphaIndex: LibraryAlphaIndex, scrollIndex: Int): AlphaSection {
    return alphaIndex.sectionNearScrollIndex(scrollIndex) ?: alphaIndex.sections.first()
}

internal fun updateAlphaScrubSelection(
    alphaIndex: LibraryAlphaIndex,
    yPx: Float,
    heightPx: Float,
): AlphaSection {
    val letterIndex = indexForVerticalPosition(
        yPx = yPx,
        count = alphaIndex.letters.size,
        heightPx = heightPx,
        zoneFraction = null,
    )
    val letter = alphaIndex.letters[letterIndex]
    return alphaIndex.sections.firstOrNull { it.letter == letter } ?: alphaIndex.sections.first()
}

@Composable
internal fun LibraryScrubVisuals(
    controller: ScrubController,
    dateIndex: LibraryDateIndex,
    modifier: Modifier = Modifier,
    screenEdgeGutter: Dp = SCROLLBAR_SCREEN_GUTTER,
    scrimColor: Color? = null,
) {
    val open = controller.overlayOpen
    val selection = controller.selection
    if (!open || selection == null) return
    val resolvedScrim = scrimColor ?: LightThemeTokens.colors.background

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(color = resolvedScrim, size = Size(size.width, size.height))
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                }
            },
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .padding(end = screenEdgeGutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val monthsInYear = dateIndex.monthsForYear(selection.selectedYear)
            ScrubWheelColumn(
                labels = monthsInYear.map { monthLabel(it.month) },
                selectedIndex = selection.selectedMonth?.let { selected ->
                    monthsInYear.indexOfFirst { it.month == selected.month }
                }?.takeIf { it >= 0 },
                modifier = Modifier.width(SCRUB_MONTHS_COLUMN_WIDTH),
            )
            Spacer(Modifier.width(SCRUB_COLUMN_GAP))
            ScrubWheelColumn(
                labels = dateIndex.years.map { it.toString() },
                selectedIndex = dateIndex.years.indexOf(selection.selectedYear).takeIf { it >= 0 },
                modifier = Modifier.width(SCRUB_YEARS_COLUMN_WIDTH),
            )
        }
    }
}

@Composable
private fun scrubWheelVariant(distanceFromCenter: Int): LightTextVariant = when (distanceFromCenter) {
    0 -> LightTextVariant.Copy
    1 -> LightTextVariant.Detail
    2 -> LightTextVariant.Superfine
    else -> LightTextVariant.Micro
}

@Composable
private fun scrubWheelLineHeight(distanceFromCenter: Int): Dp = when (distanceFromCenter) {
    0 -> legacyNToGridDp(24)
    1 -> legacyNToGridDp(18)
    2 -> legacyNToGridDp(16)
    else -> legacyNToGridDp(14)
}

@Composable
private fun ScrubWheelColumn(
    labels: List<String>,
    selectedIndex: Int?,
    modifier: Modifier = Modifier,
) {
    if (labels.isEmpty() || selectedIndex == null) return
    val colors = LightThemeTokens.colors

    Box(
        modifier = modifier.fillMaxHeight(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopEnd,
        ) {
            val density = LocalDensity.current
            val visibleItems = scrubWheelVisibleItems(
                selectedIndex = selectedIndex,
                count = labels.size,
            )
            val slotCount = SCRUB_WHEEL_VISIBLE_COUNT

            visibleItems.forEach { (itemIndex, slotIndex) ->
                val slotFraction = if (slotCount > 1) {
                    slotIndex.toFloat() / (slotCount - 1)
                } else {
                    0.5f
                }
                val slotCenterY = maxHeight * slotFraction
                val distanceFromCenter = abs(slotIndex - SCRUB_WHEEL_CENTER_SLOT)
                val (alpha, blurRadiusDp) = when (distanceFromCenter) {
                    0 -> 1f to 0.dp
                    1 -> 0.5f to 0.dp
                    2 -> 0.35f to 0.dp
                    else -> 0.25f to 3.dp
                }
                val textColor = if (distanceFromCenter == 0) {
                    colors.content
                } else {
                    PhonoSemanticColors.InactiveTab
                }
                val blurRadiusPx = with(density) { blurRadiusDp.toPx() }
                val lineHeight = scrubWheelLineHeight(distanceFromCenter)

                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = slotCenterY - lineHeight / 2)
                        .graphicsLayer {
                            this.alpha = alpha
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurRadiusPx > 0f) {
                                renderEffect = BlurEffect(blurRadiusPx, blurRadiusPx)
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LightText(
                        text = labels[itemIndex],
                        variant = scrubWheelVariant(distanceFromCenter),
                        color = textColor,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(SCRUB_DEADZONE_TICK_GAP))
                    Box(
                        Modifier
                            .width(SCRUB_DEADZONE_TICK_WIDTH)
                            .height(SCRUB_DEADZONE_TICK_HEIGHT)
                            .background(textColor),
                    )
                }
            }
        }
    }
}

/** Legacy all-labels column — still used by alpha scrub. */
@Composable
private fun ScrubLabelColumn(
    labels: List<String>,
    selectedIndex: Int?,
    modifier: Modifier = Modifier,
) {
    val colors = LightThemeTokens.colors
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = legacyNToGridDp(12)),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.End,
    ) {
        labels.forEachIndexed { index, label ->
            LightText(
                text = label,
                variant = LightTextVariant.Detail,
                color = if (index == selectedIndex) colors.content else PhonoSemanticColors.InactiveTab,
                modifier = Modifier.padding(vertical = legacyNToGridDp(4)),
            )
        }
    }
}

internal data class ScrubSelectionState(
    val selectedYear: Int,
    val selectedMonth: MonthSection?,
)

internal fun initialScrubSelection(dateIndex: LibraryDateIndex, scrollIndex: Int): ScrubSelectionState {
    val section = dateIndex.sectionNearScrollIndex(scrollIndex)
    val year = section?.year ?: dateIndex.years.first()
    val month = section?.let { dateIndex.representativeSection(it) }
        ?: dateIndex.monthsForYear(year).firstOrNull()
    return ScrubSelectionState(
        selectedYear = year,
        selectedMonth = month,
    )
}

internal fun updateScrubSelection(
    dateIndex: LibraryDateIndex,
    current: ScrubSelectionState,
    column: ScrubColumn,
    yPx: Float,
    heightPx: Float,
): ScrubSelectionState {
    return when (column) {
        ScrubColumn.Years -> {
            val yearIndex = indexForVerticalPosition(
                yPx = yPx,
                count = dateIndex.years.size,
                heightPx = heightPx,
            )
            val year = dateIndex.years[yearIndex]
            val months = dateIndex.monthsForYear(year)
            val month = when {
                current.selectedMonth?.year == year ->
                    dateIndex.representativeSection(current.selectedMonth)
                        ?: months.firstOrNull()
                else -> months.firstOrNull()
            }
            current.copy(
                selectedYear = year,
                selectedMonth = month,
            )
        }

        ScrubColumn.Months -> {
            val months = dateIndex.monthsForYear(current.selectedYear)
            if (months.isEmpty()) {
                current
            } else {
                val monthIndex = indexForVerticalPosition(
                    yPx = yPx,
                    count = months.size,
                    heightPx = heightPx,
                )
                current.copy(selectedMonth = months[monthIndex])
            }
        }

        ScrubColumn.Scrollbar -> current
    }
}
