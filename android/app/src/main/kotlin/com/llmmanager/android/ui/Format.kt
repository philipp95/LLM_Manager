package com.llmmanager.android.ui

import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

// Formatters are built per call so a locale change while the app runs is honoured.
fun shortDay(iso: String): String = parseDay(iso)?.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())) ?: iso

fun longDay(iso: String): String = parseDay(iso)?.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())) ?: iso

private fun parseDay(iso: String): LocalDate? = try {
    LocalDate.parse(iso)
} catch (_: DateTimeParseException) {
    null
}

/** "3h ago", "2d ago"; [iso] is an ISO-8601 timestamp with offset. */
fun relativeTime(iso: String?, now: OffsetDateTime = OffsetDateTime.now()): String {
    val then = iso?.let {
        try {
            OffsetDateTime.parse(it)
        } catch (_: DateTimeParseException) {
            null
        }
    }
    if (then == null) return iso ?: "no commits"
    val seconds = Duration.between(then, now).seconds
    val units = listOf("y" to 31_536_000L, "mo" to 2_592_000L, "d" to 86_400L, "h" to 3_600L, "m" to 60L)
    return units.firstOrNull { seconds >= it.second }?.let { (label, size) -> "${seconds / size}$label ago" } ?: "just now"
}

fun plural(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"
