package com.llmmanager.server.api

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/** Next firing times for a [Schedule]. Pure, so the rules are testable without a clock. */
object ScheduleTimes {
    private const val DAYS_TO_SEARCH = 8L

    /** Validates a schedule as submitted; returns a message for the user, or null when it is usable. */
    fun problem(schedule: Schedule): String? = when {
        schedule.at != null && schedule.time != null -> "Choose either a single time or a daily time, not both"
        schedule.at != null -> if (instant(schedule.at) == null) "Invalid date and time" else null
        schedule.time == null -> "Choose when the job runs"
        localTime(schedule.time) == null -> "Invalid time of day"
        zone(schedule.zone) == null -> "Unknown time zone"
        schedule.days.any { it !in 1..7 } -> "Weekdays must be 1 (Monday) to 7 (Sunday)"
        else -> null
    }

    /** The first firing strictly after [after], or null when a one-off job is finished. */
    fun next(job: Job, after: Instant): Instant? =
        if (job.schedule.at != null) instant(job.schedule.at).takeUnless { job.done } else nextRepeat(job.schedule, after)

    private fun nextRepeat(schedule: Schedule, after: Instant): Instant? {
        val time = localTime(schedule.time)
        val zone = zone(schedule.zone)
        if (time == null || zone == null) return null
        val start = ZonedDateTime.ofInstant(after, zone).toLocalDate()
        return (0..DAYS_TO_SEARCH).asSequence()
            .map { start.plusDays(it) }
            .filter { schedule.days.isEmpty() || it.dayOfWeek.value in schedule.days }
            .map { ZonedDateTime.of(it, time, zone).toInstant() }
            .firstOrNull { it.isAfter(after) }
    }

    /** Whether [job] should fire at [now]: due, enabled, and not already fired for this slot. */
    fun isDue(job: Job, now: Instant): Boolean {
        if (!job.enabled || job.done) return false
        val last = job.lastFiredAt?.let(::instant) ?: instant(job.createdAt) ?: return false
        // A repeating job fires once per slot; a one-off fires once its time has come.
        val due = if (job.schedule.at != null) instant(job.schedule.at) else next(job, last)
        return due != null && !due.isAfter(now)
    }

    fun instant(text: String?): Instant? = try {
        text?.let(Instant::parse)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun localTime(text: String?): LocalTime? = try {
        text?.let(LocalTime::parse)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun zone(text: String?): ZoneId? = try {
        text?.let(ZoneId::of)
    } catch (_: DateTimeException) {
        null
    }
}
