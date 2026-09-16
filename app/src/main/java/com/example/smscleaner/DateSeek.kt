package com.example.smscleaner

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.TextStyle
import java.util.Locale

/**
 * Parses Google Messages' sticky date-divider headers ("Today", "Yesterday", "Monday",
 * "Jan 5", "Jan 5, 2023", ...) into a LocalDate, so the seek phase can tell how far back
 * in the thread it's currently scrolled.
 *
 * Unverified against a real device -- exact divider text/format may differ (locale,
 * app version). Use "Dump current screen tree to Logcat" while a divider is visible and
 * check the dumped text against the patterns below; add/adjust formats as needed.
 */
object DateSeek {

    private val YEAR_FORMATS = listOf("MMM d, yyyy", "M/d/yy", "M/d/yyyy", "EEE, MMM d, yyyy")
        .map { DateTimeFormatter.ofPattern(it, Locale.US) }

    private val NO_YEAR_PATTERNS = listOf("MMM d", "EEE, MMM d")

    private val WEEKDAY_NAMES: Map<String, DayOfWeek> =
        DayOfWeek.values().associateBy { it.getDisplayName(TextStyle.FULL, Locale.US).lowercase() }

    /** Returns null if [rawText] doesn't look like a date-divider string at all. */
    fun parse(rawText: String, today: LocalDate = LocalDate.now()): LocalDate? {
        val text = rawText.trim()
        if (text.isEmpty() || text.length > 24) return null // dividers are short; skip long strings fast

        when (text.lowercase(Locale.US)) {
            "today" -> return today
            "yesterday" -> return today.minusDays(1)
        }

        WEEKDAY_NAMES[text.lowercase(Locale.US)]?.let { dow ->
            // A bare weekday name means "the most recent past occurrence of that weekday",
            // matching how chat apps label the last ~7 days.
            var candidate = today
            repeat(7) {
                if (candidate.dayOfWeek == dow) return candidate
                candidate = candidate.minusDays(1)
            }
            return null
        }

        for (formatter in YEAR_FORMATS) {
            try {
                return LocalDate.parse(text, formatter)
            } catch (e: DateTimeParseException) {
                // try next format
            }
        }

        for (pattern in NO_YEAR_PATTERNS) {
            try {
                val formatter = DateTimeFormatter.ofPattern("$pattern yyyy", Locale.US)
                val parsed = LocalDate.parse("$text ${today.year}", formatter)
                // "Jan 5" with no year could be this year or last year; if it'd be in the
                // future assume it meant last year instead.
                return if (parsed.isAfter(today)) parsed.minusYears(1) else parsed
            } catch (e: DateTimeParseException) {
                // try next pattern
            }
        }

        return null
    }
}
