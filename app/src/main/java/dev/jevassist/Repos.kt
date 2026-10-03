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

    /**
     * WhatsApp adds its own entry to contacts that use WhatsApp, holding the exact WhatsApp ID
     * ("15551234567@s.whatsapp.net"). Using it avoids guessing the country code.
     */
    fun whatsappNumber(contactId: Long): String? {
        ctx.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.DATA1),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} IN (?, ?)",
            arrayOf(
                contactId.toString(),
                "vnd.android.cursor.item/vnd.com.whatsapp.profile",
                "vnd.android.cursor.item/vnd.com.whatsapp.w4b.profile",
            ),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val jid = c.getString(0) ?: continue
                val digits = jid.substringBefore('@').filter { it.isDigit() }
                if (digits.length >= 6) return digits
            }
        }
        return null
    }
}

data class CalendarInfo(val id: Long, val name: String, val account: String)
data class AgendaItem(val title: String, val begin: ZonedDateTime, val allDay: Boolean)

class CalendarRepo(private val ctx: Context) {

    fun canRead() = ctx.granted(Manifest.permission.READ_CALENDAR)
    fun canWrite() = ctx.granted(Manifest.permission.WRITE_CALENDAR) && canRead()

    /**
     * The calendar new reminders/events go to. Prefers your Google account's own calendar
     * (so it syncs and Google Calendar notifies you), over phone-only or shared calendars.
     */
    fun writableCalendar(): CalendarInfo? {
        if (!canWrite()) return null
        var best: CalendarInfo? = null
        var bestScore = -1
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.VISIBLE,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.Calendars.OWNER_ACCOUNT,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val access = c.getInt(2)
                if (access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                val account = c.getString(4) ?: ""
                val type = c.getString(5) ?: ""
                val owner = c.getString(6) ?: ""
                var score = 0
                if (c.getInt(3) == 1) score += 1                 // visible
                if (type == "com.google") score += 4              // syncs with Google Calendar
                if (owner.isNotEmpty() && owner == account) score += 2  // your own calendar, not a shared one
                if (access >= CalendarContract.Calendars.CAL_ACCESS_OWNER) score += 1
                if (score > bestScore) {
                    bestScore = score
                    best = CalendarInfo(c.getLong(0), c.getString(1) ?: "Calendar", account)
                }
            }
        }
        return best
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

data class LaunchApp(val pkg: String, val label: String)

object LaunchableApps {
    /** User-launchable apps, for "open X and …" tasks. */
    @Suppress("DEPRECATION")
    fun list(ctx: Context): List<LaunchApp> {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0)
            .map { LaunchApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }
}

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
