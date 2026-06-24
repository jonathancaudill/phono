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

    fun monthsForYear(year: Int): List<MonthSection> = sections.filter { it.year == year }

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

    val years = sections.map { it.year }.distinct()
    return LibraryDateIndex(years = years, sections = sections)
}

fun parseAddedAtYearMonth(iso: String): YearMonth? = runCatching {
    YearMonth.from(Instant.parse(iso).atZone(ZoneOffset.UTC))
}.getOrNull()

/** Section whose [MonthSection.startIndex] is nearest at or before [scrollIndex]. */
fun LibraryDateIndex.sectionNearScrollIndex(scrollIndex: Int): MonthSection? {
    if (sections.isEmpty()) return null
    return sections.lastOrNull { it.startIndex <= scrollIndex } ?: sections.first()
}

fun monthLabel(month: Month): String =
    month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
