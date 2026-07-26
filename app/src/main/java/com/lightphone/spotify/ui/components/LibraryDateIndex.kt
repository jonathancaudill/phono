package com.lightphone.spotify.ui.components

import java.time.Instant
import java.time.Month
import java.time.YearMonth
import java.time.ZoneOffset

data class MonthSection(
    val year: Int,
    val month: Month,
    val startIndex: Int,
)

data class LibraryDateIndex(
    val years: List<Int>,
    val sections: List<MonthSection>,
) {
    val isEmpty: Boolean get() = sections.isEmpty()

    /** One section per calendar month in that year (first list position), in scroll order. */
    fun monthsForYear(year: Int): List<MonthSection> =
        sections
            .filter { it.year == year }
            .groupBy { YearMonth.of(it.year, it.month) }
            .values
            .map { group -> group.minBy { it.startIndex } }
            .sortedBy { it.startIndex }

    fun representativeSection(section: MonthSection): MonthSection? =
        monthsForYear(section.year).find { it.month == section.month }

    companion object {
        val Empty = LibraryDateIndex(years = emptyList(), sections = emptyList())
    }
}

fun <T> buildLibraryDateIndex(
    items: List<T>,
    addedAt: (T) -> String?,
): LibraryDateIndex {
    if (items.isEmpty()) return LibraryDateIndex.Empty

    val sections = mutableListOf<MonthSection>()
    var lastYearMonth: YearMonth? = null

    items.forEachIndexed { index, item ->
        val raw = addedAt(item) ?: return@forEachIndexed
        val ym = parseAddedAtYearMonth(raw) ?: return@forEachIndexed
        if (ym != lastYearMonth) {
            sections.add(MonthSection(year = ym.year, month = ym.month, startIndex = index))
            lastYearMonth = ym
        }
    }

    if (sections.isEmpty()) return LibraryDateIndex.Empty

    val years = sections.map { it.year }.distinct().sortedDescending()
    return LibraryDateIndex(years = years, sections = sections)
}

fun parseAddedAtYearMonth(iso: String): YearMonth? {
    // Fast path: ISO-8601 timestamps ("2023-05-15T10:30:00Z") begin with
    // "yyyy-MM-dd". Substring-parse the year/month directly to avoid a per-item
    // Instant.parse — which allocates and is exception-guarded — running on the
    // composition/main thread for the whole library on every tab switch.
    if (iso.length >= 10 && iso[4] == '-' && iso[7] == '-') {
        val year = iso.substring(0, 4).toIntOrNull()
        val month = iso.substring(5, 7).toIntOrNull()
        if (year != null && month != null && month in 1..12) {
            return YearMonth.of(year, month)
        }
    }
    // Fallback for any unexpected format.
    return runCatching {
        YearMonth.from(Instant.parse(iso).atZone(ZoneOffset.UTC))
    }.getOrNull()
}

/** Section whose [MonthSection.startIndex] is nearest at or before [scrollIndex]. */
fun LibraryDateIndex.sectionNearScrollIndex(scrollIndex: Int): MonthSection? {
    if (sections.isEmpty()) return null
    return sections.lastOrNull { it.startIndex <= scrollIndex } ?: sections.first()
}

fun monthLabel(month: Month): String =
    month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
