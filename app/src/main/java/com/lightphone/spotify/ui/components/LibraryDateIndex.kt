package com.lightphone.spotify.ui.components

import com.lightphone.spotify.debug.DebugSessionLog
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
    val index = LibraryDateIndex(years = years, sections = sections)
    // #region agent log
    val monthDupesByYear = years.associateWith { year ->
        val months = sections.filter { it.year == year }.map { it.month.name }
        months.groupingBy { it }.eachCount().filter { it.value > 1 }
    }
    val dedupedDupesByYear = years.associateWith { year ->
        val labels = index.monthsForYear(year).map { it.month.name }
        labels.groupingBy { it }.eachCount().filter { it.value > 1 }
    }
    var monotonicViolations = 0
    var lastParsed: YearMonth? = null
    items.forEach { item ->
        val raw = addedAt(item) ?: return@forEach
        val ym = parseAddedAtYearMonth(raw) ?: return@forEach
        if (lastParsed != null && ym > lastParsed) monotonicViolations++
        lastParsed = ym
    }
    DebugSessionLog.log(
        location = "LibraryDateIndex.kt:buildLibraryDateIndex",
        message = "date index built",
        hypothesisId = "A,B,C,E",
        runId = "post-fix",
        data = mapOf(
            "itemCount" to items.size,
            "sectionCount" to sections.size,
            "years" to years.toString(),
            "yearsSortedDesc" to years.sortedDescending().toString(),
            "yearsMatchSorted" to (years == years.sortedDescending()),
            "sectionSummary" to sections.map { "${it.year}-${it.month.name}@${it.startIndex}" }.toString(),
            "duplicateMonthsByYear" to monthDupesByYear.filter { it.value.isNotEmpty() }.toString(),
            "dedupedDuplicateMonthsByYear" to dedupedDupesByYear.filter { it.value.isNotEmpty() }.toString(),
            "monotonicViolations" to monotonicViolations,
            "nullAddedAtCount" to items.count { addedAt(it) == null },
            "unparseableAddedAtCount" to items.count {
                val raw = addedAt(it)
                raw != null && parseAddedAtYearMonth(raw) == null
            },
        ),
    )
    // #endregion
    return index
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
