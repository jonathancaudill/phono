package com.lightphone.spotify.ui.components

import org.junit.Test
import java.io.File

/**
 * Runs buildLibraryDateIndex on the host and writes NDJSON to the debug log file.
 * Exercises production index logic without a device.
 */
class LibraryDateIndexDebugTest {
    private data class FakeAlbum(val addedAt: String?)

    private fun writeLog(message: String, hypothesisId: String, runId: String, data: Map<String, Any?>) {
        val dataFields = data.entries.joinToString(",") { (key, value) ->
            """"$key":${jsonValue(value)}"""
        }
        val line =
            """{"sessionId":"0d7c80","timestamp":${System.currentTimeMillis()},"location":"LibraryDateIndexDebugTest","message":"$message","hypothesisId":"$hypothesisId","runId":"$runId","data":{$dataFields}}"""
        File("/Users/jonathancaudill/Programming/Phono/.cursor/debug-0d7c80.log")
            .appendText(line + "\n")
    }

    private fun jsonValue(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Number -> value.toString()
        else -> "\"${value.toString().replace("\"", "\\\"")}\""
    }

    @Test
    fun nonMonotonicLibraryDedupesMonthsAndSortsYears() {
        val items = listOf(
            FakeAlbum("2025-06-15T12:00:00Z"),
            FakeAlbum("2025-06-10T12:00:00Z"),
            FakeAlbum("2026-06-01T12:00:00Z"),
            FakeAlbum("2022-03-01T12:00:00Z"),
            FakeAlbum("2025-06-01T12:00:00Z"),
            FakeAlbum("2023-06-01T12:00:00Z"),
            FakeAlbum("2024-04-01T12:00:00Z"),
            FakeAlbum("2025-06-20T12:00:00Z"),
            FakeAlbum("2019-04-01T12:00:00Z"),
            FakeAlbum("2021-05-01T12:00:00Z"),
        )
        val index = buildLibraryDateIndex(items) { it.addedAt }
        val months2025 = index.monthsForYear(2025).map { monthLabel(it.month) }

        writeLog(
            message = "non-monotonic fixture",
            hypothesisId = "A,B,E",
            runId = "post-fix",
            data = mapOf(
                "years" to index.years.toString(),
                "yearsMatchSorted" to (index.years == index.years.sortedDescending()),
                "months2025" to months2025.toString(),
                "duplicateJuneCount2025" to months2025.count { it == "June" },
            ),
        )

        assert(index.years == index.years.sortedDescending())
        assert(months2025 == listOf("June"))
    }

    @Test
    fun monotonicLibraryProducesCleanIndex() {
        val items = listOf(
            FakeAlbum("2026-06-01T12:00:00Z"),
            FakeAlbum("2025-06-01T12:00:00Z"),
            FakeAlbum("2025-04-01T12:00:00Z"),
            FakeAlbum("2022-03-01T12:00:00Z"),
        )
        val index = buildLibraryDateIndex(items) { it.addedAt }
        val months2025 = index.monthsForYear(2025).map { monthLabel(it.month) }

        writeLog(
            message = "monotonic fixture",
            hypothesisId = "A,B",
            runId = "post-fix",
            data = mapOf(
                "years" to index.years.toString(),
                "months2025" to months2025.toString(),
            ),
        )

        assert(index.years == listOf(2026, 2025, 2022))
        assert(months2025 == listOf("June", "April"))
    }
}
