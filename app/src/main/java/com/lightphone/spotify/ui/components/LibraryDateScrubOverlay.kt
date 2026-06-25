package com.lightphone.spotify.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.lightphone.spotify.ui.theme.MonoColors
import com.lightphone.spotify.ui.theme.n
import kotlin.math.roundToInt

internal val SCRUBBAR_TOUCH_WIDTH: Dp = n(24)
internal val SCRUB_YEARS_COLUMN_WIDTH: Dp = n(48)
internal val SCRUB_MONTHS_COLUMN_WIDTH: Dp = n(96)
private val SCRUB_COLUMN_GAP: Dp = n(20)

internal enum class ScrubColumn {
    Months,
    Years,
    Scrollbar,
}

internal fun scrubColumnAt(xPx: Float, totalWidthPx: Float, density: Float): ScrubColumn {
    val scrollbarW = SCRUBBAR_TOUCH_WIDTH.value * density
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

internal fun indexForVerticalPosition(yPx: Float, count: Int, heightPx: Float): Int {
    if (count <= 0) return 0
    if (count == 1) return 0
    val fraction = (yPx / heightPx).coerceIn(0f, 1f)
    return (fraction * (count - 1)).roundToInt()
}

/** Scrim + labels only — composed separately from the LazyColumn so open/close is instant. */
@Composable
internal fun LibraryScrubVisuals(
    controller: ScrubController,
    dateIndex: LibraryDateIndex,
    modifier: Modifier = Modifier,
    scrimColor: Color = MonoColors.Background,
) {
    val open = controller.overlayOpen
    val selection = controller.selection
    if (!open || selection == null) return

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(color = scrimColor, size = Size(size.width, size.height))
            },
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .padding(end = SCRUBBAR_TOUCH_WIDTH),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val monthsInYear = dateIndex.monthsForYear(selection.selectedYear)
            ScrubLabelColumn(
                labels = monthsInYear.map { monthLabel(it.month) },
                selectedIndex = monthsInYear.indexOf(selection.selectedMonth).takeIf { it >= 0 },
                modifier = Modifier.width(SCRUB_MONTHS_COLUMN_WIDTH),
            )
            Spacer(Modifier.width(SCRUB_COLUMN_GAP))
            ScrubLabelColumn(
                labels = dateIndex.years.map { it.toString() },
                selectedIndex = dateIndex.years.indexOf(selection.selectedYear).takeIf { it >= 0 },
                modifier = Modifier.width(SCRUB_YEARS_COLUMN_WIDTH),
            )
        }
    }
}

@Composable
private fun ScrubLabelColumn(
    labels: List<String>,
    selectedIndex: Int?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = n(12)),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.End,
    ) {
        labels.forEachIndexed { index, label ->
            StyledText(
                text = label,
                modifier = Modifier.padding(vertical = n(4)),
                size = 15,
                color = if (index == selectedIndex) {
                    MonoColors.Foreground
                } else {
                    MonoColors.InactiveTab
                },
            )
        }
    }
}

internal data class ScrubSelectionState(
    val selectedYear: Int,
    val selectedMonth: MonthSection?,
    val reachedMonthsZone: Boolean,
)

internal fun initialScrubSelection(dateIndex: LibraryDateIndex, scrollIndex: Int): ScrubSelectionState {
    val section = dateIndex.sectionNearScrollIndex(scrollIndex)
    val year = section?.year ?: dateIndex.years.first()
    val month = section?.takeIf { it.year == year }
        ?: dateIndex.monthsForYear(year).firstOrNull()
    return ScrubSelectionState(
        selectedYear = year,
        selectedMonth = month,
        reachedMonthsZone = false,
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
                current.selectedMonth?.year == year -> current.selectedMonth
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
                current.copy(reachedMonthsZone = true)
            } else {
                val monthIndex = indexForVerticalPosition(
                    yPx = yPx,
                    count = months.size,
                    heightPx = heightPx,
                )
                current.copy(
                    selectedMonth = months[monthIndex],
                    reachedMonthsZone = true,
                )
            }
        }

        ScrubColumn.Scrollbar -> current
    }
}
