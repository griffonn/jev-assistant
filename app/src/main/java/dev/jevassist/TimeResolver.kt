package dev.jevassist

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Turns Jev's date/time *parts* (month, day, weekday, hour, minute, am/pm …) into real
 * dates and times. Jev only reads what was said; all calendar math happens here, as
 * TypeSafe recommends (Jev is unreliable at date arithmetic).
 *
 * Pure code (no Android), so it is unit-testable on any JVM.
 */
class TimeResolver(
    private val now: ZonedDateTime,
    private val utterance: String,
    private val pick: (String) -> String?,
) {
    private data class ClockParts(val hour: Int, val minute: Int, val period: String)

    private val today: LocalDate get() = now.toLocalDate()

    /** Next time the alarm should ring (only hour/minute are used by the clock app). */
    fun alarmTime(): LocalTime? {
        if (pick("time_mode") == "in_duration") {
            TextTools.parseDurationSeconds(utterance)?.let { return now.plusSeconds(it.toLong()).toLocalTime() }
        }
        val cp = clockParts() ?: return null
        val date = date()
        val h24 = to24(cp.hour, cp.period)
            ?: if (date != null && date != today) cp.hour % 12 // "alarm tomorrow at 7" -> 7 AM
            else nextAmbiguous(cp.hour, cp.minute).hour
        return LocalTime.of(h24, cp.minute)
    }

    /** Start for reminders/events: (exact date-time or null, date-only or null). */
    fun start(): Pair<ZonedDateTime?, LocalDate?> {
        if (pick("time_mode") == "in_duration") {
            TextTools.parseDurationSeconds(utterance)?.let { return now.plusSeconds(it.toLong()) to null }
        }
        val date = date()
        val cp = clockParts() ?: return null to date
        val explicit = to24(cp.hour, cp.period)
        if (date == null || date == today) {
            if (explicit != null) {
                var t = ZonedDateTime.of(today, LocalTime.of(explicit, cp.minute), now.zone)
                if (date == null && !t.isAfter(now)) t = t.plusDays(1)
                return t to null
            }
            return nextAmbiguous(cp.hour, cp.minute) to null
        }
        // A future day with no AM/PM: 1-6 -> afternoon, 7-11 -> morning, 12 -> noon.
        val h = explicit ?: when (val h12 = cp.hour % 12) {
            0 -> 12
            in 1..6 -> h12 + 12
            else -> h12
        }
        return ZonedDateTime.of(date, LocalTime.of(h, cp.minute), now.zone) to null
    }

    fun date(): LocalDate? = when (pick("date_mode")) {
        "absolute" -> {
            val month = MONTHS.indexOf(pick("month")) + 1
            val day = pick("day")?.toIntOrNull()
            if (month <= 0 || day == null) null
            else try {
                val d = LocalDate.of(today.year, month, day)
                if (d.isBefore(today)) d.plusYears(1) else d
            } catch (e: DateTimeException) {
                null
            }
        }
        "relative" -> when (pick("day_anchor")) {
            "today" -> today
            "tomorrow" -> today.plusDays(1)
            "day_after" -> today.plusDays(2)
            "weekday" -> {
                val w = WEEKDAYS.indexOf(pick("weekday"))
                if (w < 0) null else weekday(w, pick("week_offset"))
            }
            else -> null
        }
        else -> null
    }

    private fun weekday(w: Int, offset: String?): LocalDate {
        val todayIdx = today.dayOfWeek.value - 1
        val monday = today.minusDays(todayIdx.toLong())
        val d = when (offset) {
            "next" -> monday.plusDays(7L + w)
            "current" -> monday.plusDays(w.toLong())
            else -> today.plusDays(((w - todayIdx + 7) % 7).toLong())
        }
        return if (d.isBefore(today)) d.plusDays(7) else d
    }

    private fun clockParts(): ClockParts? {
        if (pick("time_mode") != "clock") return null
        val h = pick("hour")?.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val m = pick("minute")?.toIntOrNull()?.takeIf { it in 0..59 } ?: 0
        return ClockParts(h, m, pick("period") ?: "not_stated")
    }

    /** 24h hour, or null when AM/PM is ambiguous. */
    private fun to24(h: Int, period: String): Int? = when {
        h > 12 || h == 0 -> h
        period == "am" -> h % 12
        period == "pm" -> h % 12 + 12
        else -> null
    }

    /** "at 7" with no AM/PM: whichever of 7:00 / 19:00 comes next. */
    private fun nextAmbiguous(h: Int, m: Int): ZonedDateTime {
        val candidates = listOf(h % 12, h % 12 + 12).map { ZonedDateTime.of(today, LocalTime.of(it, m), now.zone) }
        return candidates.firstOrNull { it.isAfter(now) } ?: candidates[0].plusDays(1)
    }
}
