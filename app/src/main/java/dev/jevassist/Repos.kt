package dev.jevassist

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

fun Context.granted(permission: String): Boolean =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

data class Contact(val id: Long, val name: String, val starred: Boolean)
data class PhoneNumber(val number: String, val type: Int, val primary: Boolean)

class ContactsRepo(private val ctx: Context) {

    fun hasPermission() = ctx.granted(Manifest.permission.READ_CONTACTS)

    private fun all(): List<Contact> {
        val out = ArrayList<Contact>()
        ctx.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.STARRED,
            ),
            "${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1",
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                out.add(Contact(c.getLong(0), name, c.getInt(2) == 1))
            }
        }
        return out
    }

    /**
     * Pre-filters the address book in code so Jev only chooses among plausible people
     * (a Choice allows at most 255 options, and less noise means better accuracy).
     */
    fun candidates(utterance: String, max: Int = 120): List<Contact> {
        val everyone = all()
        if (everyone.isEmpty()) return emptyList()
        val uTokens = TextTools.normalize(utterance).split(" ").filter { it.length >= 2 }.toSet()

        val scored = everyone.map { c ->
            var score = 0
            val nTokens = TextTools.normalize(c.name).split(" ").filter { it.length >= 2 }
            for (nt in nTokens) for (ut in uTokens) {
                score += when {
                    nt == ut -> 3
                    nt.length >= 4 && ut.length >= 4 && (nt.startsWith(ut) || ut.startsWith(nt)) -> 2
                    nt.length >= 5 && ut.length >= 5 && TextTools.levenshtein(nt, ut) <= 1 -> 1
                    else -> 0
                }
            }
            c to score
        }
        val result = LinkedHashSet<Contact>()
        scored.filter { it.second > 0 }.sortedByDescending { it.second }.forEach { result.add(it.first) }
        // Favorites help with nicknames the recognizer may spell differently.
        everyone.filter { it.starred }.forEach { result.add(it) }
        // Small address books can simply be sent whole.
        if (everyone.size <= max) everyone.forEach { result.add(it) }
        return result.take(max)
    }

    fun numbers(contactId: Long): List<PhoneNumber> {
        val out = ArrayList<PhoneNumber>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
            ),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val num = c.getString(0) ?: continue
                out.add(PhoneNumber(num, c.getInt(1), c.getInt(2) == 1))
            }
        }
        return out.distinctBy { it.number.filter { ch -> ch.isDigit() } }
    }
}

data class CalendarInfo(val id: Long, val name: String)
data class AgendaItem(val title: String, val begin: ZonedDateTime, val allDay: Boolean)

class CalendarRepo(private val ctx: Context) {

    fun canRead() = ctx.granted(Manifest.permission.READ_CALENDAR)
    fun canWrite() = ctx.granted(Manifest.permission.WRITE_CALENDAR) && canRead()

    /** The primary visible calendar you can write to (usually your Google account's). */
    fun writableCalendar(): CalendarInfo? {
        if (!canWrite()) return null
        var fallback: CalendarInfo? = null
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.VISIBLE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val access = c.getInt(3)
                val visible = c.getInt(4) == 1
                if (access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR || !visible) continue
                val info = CalendarInfo(c.getLong(0), c.getString(1) ?: "Calendar")
                if (c.getInt(2) == 1) return info
                if (fallback == null) fallback = info
            }
        }
        return fallback
    }

    /** Inserts an event directly. Returns null if it couldn't (caller falls back to the calendar app). */
    fun insert(
        cal: CalendarInfo,
        title: String,
        start: ZonedDateTime?,
        allDayDate: LocalDate?,
        minutes: Int,
        alertMinutesBefore: Int?,
    ): Long? {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, cal.id)
            put(CalendarContract.Events.TITLE, title)
            if (allDayDate != null) {
                val startUtc = allDayDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                put(CalendarContract.Events.DTSTART, startUtc)
                put(CalendarContract.Events.DTEND, startUtc + 86_400_000L)
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            } else if (start != null) {
                val ms = start.toInstant().toEpochMilli()
                put(CalendarContract.Events.DTSTART, ms)
                put(CalendarContract.Events.DTEND, ms + minutes * 60_000L)
                put(CalendarContract.Events.EVENT_TIMEZONE, start.zone.id)
            } else {
                return null
            }
            if (alertMinutesBefore != null) put(CalendarContract.Events.HAS_ALARM, 1)
        }
        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return null
        val eventId = uri.lastPathSegment?.toLongOrNull() ?: return null
        if (alertMinutesBefore != null) {
            val r = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, alertMinutesBefore)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, r)
        }
        return eventId
    }

    fun eventsOn(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<AgendaItem> {
        if (!canRead()) return emptyList()
        val begin = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, begin)
        ContentUris.appendId(builder, end)
        val out = ArrayList<AgendaItem>()
        ctx.contentResolver.query(
            builder.build(),
            arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.ALL_DAY,
            ),
            null, null,
            "${CalendarContract.Instances.ALL_DAY} DESC, ${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "(no title)"
                val at = Instant.ofEpochMilli(c.getLong(1)).atZone(zone)
                out.add(AgendaItem(title, at, c.getInt(2) == 1))
            }
        }
        return out
    }
}

data class MediaApp(val pkg: String, val label: String)

object MediaApps {
    /** Installed apps that can "play X" from a search query (Spotify, YouTube Music, ...). */
    @Suppress("DEPRECATION")
    fun list(ctx: Context): List<MediaApp> {
        val pm = ctx.packageManager
        return pm.queryIntentActivities(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH), 0)
            .map { MediaApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.pkg }
    }
}
