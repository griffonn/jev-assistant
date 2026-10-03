package dev.jevassist

import android.Manifest
import android.app.Activity
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.view.KeyEvent
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

sealed class Outcome {
    /** leave = another app is now in front, so close the overlay. */
    data class Done(val say: String, val leave: Boolean = false) : Outcome()
    data class Confirm(val say: String, val detail: String, val yes: () -> Outcome) : Outcome()
    data class Failed(val say: String) : Outcome()
}

class Actions(private val activity: Activity, private val prefs: AppPrefs) {

    private val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    fun run(cmd: Command): Outcome = try {
        when (cmd) {
            is Command.Timer -> {
                start(
                    Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, cmd.seconds)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                )
                Outcome.Done("Timer set for ${TextTools.formatDuration(cmd.seconds)}.")
            }
            Command.StopTimer -> {
                if (Build.VERSION.SDK_INT >= 28) {
                    start(Intent(AlarmClock.ACTION_DISMISS_TIMER))
                    Outcome.Done("Timer stopped.")
                } else {
                    start(Intent(AlarmClock.ACTION_SHOW_TIMERS))
                    Outcome.Done("Here are your timers.", leave = true)
                }
            }
            is Command.Alarm -> {
                start(
                    Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, cmd.hour)
                        .putExtra(AlarmClock.EXTRA_MINUTES, cmd.minute)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                )
                Outcome.Done("Alarm set for ${LocalTime.of(cmd.hour, cmd.minute).format(timeFmt)}.")
            }
            Command.ShowAlarms -> {
                start(Intent(AlarmClock.ACTION_SHOW_ALARMS))
                Outcome.Done("Here are your alarms.", leave = true)
            }
            is Command.Call -> {
                val uri = Uri.fromParts("tel", cmd.number, null)
                if (activity.granted(Manifest.permission.CALL_PHONE)) {
                    start(Intent(Intent.ACTION_CALL, uri))
                    Outcome.Done("Calling ${cmd.name}.", leave = true)
                } else {
                    start(Intent(Intent.ACTION_DIAL, uri))
                    Outcome.Done("Opening the dialer for ${cmd.name}.", leave = true)
                }
            }
            is Command.WhatsApp -> {
                val pkg = whatsAppPackage()
                val body = cmd.body
                if (pkg == null) {
                    Outcome.Failed("WhatsApp isn't installed.")
                } else if (body == null) {
                    openWhatsApp(pkg, cmd.waNumber, null)
                    Outcome.Done("Opening your chat with ${cmd.name}.", leave = true)
                } else {
                    Outcome.Confirm(
                        say = "WhatsApp ${cmd.name}: $body. Send it?",
                        detail = "WhatsApp to ${cmd.name}:\n“$body”",
                        yes = {
                            val auto = prefs.whatsappAutoSend && JevAccessibilityService.running
                            if (auto) AutoSend.arm(body)
                            openWhatsApp(pkg, cmd.waNumber, body)
                            Outcome.Done(
                                if (auto) "Sending to ${cmd.name}." else "Tap send in WhatsApp.",
                                leave = true,
                            )
                        },
                    )
                }
            }
            is Command.PlayMedia -> {
                val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    .putExtra(SearchManager.QUERY, cmd.query)
                    .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (cmd.pkg != null) {
                    i.setPackage(cmd.pkg)
                    prefs.lastMusicPackage = cmd.pkg
                }
                activity.startActivity(i)
                Outcome.Done("Playing ${cmd.query}" + (cmd.appLabel?.let { " on $it" } ?: "") + ".", leave = true)
            }
            is Command.MediaKey -> {
                val am = activity.getSystemService(AudioManager::class.java)
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, cmd.keyCode))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, cmd.keyCode))
                Outcome.Done(cmd.say)
            }
            is Command.Volume -> {
                val am = activity.getSystemService(AudioManager::class.java)
                val dir = if (cmd.up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                repeat(2) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI) }
                Outcome.Done(if (cmd.up) "Volume up." else "Volume down.")
            }
            is Command.Reminder ->
                addToCalendar(cmd.title, cmd.at, null, prefs.reminderEventMinutes, alert = 0, isReminder = true)
            is Command.Event -> addToCalendar(cmd.title, cmd.start, cmd.allDayDate, cmd.minutes, alert = null, isReminder = false)
            is Command.Agenda -> agenda(cmd.date)
            Command.TellTime -> {
                val now = ZonedDateTime.now()
                val date = now.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
                Outcome.Done("It's ${now.format(timeFmt)}, $date.")
            }
            is Command.WebSearch -> {
                start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(cmd.query))))
                Outcome.Done("Searching the web.", leave = true)
            }
            is Command.AppTask -> {
                val svc = JevAccessibilityService.instance
                val pkg = cmd.appPackage
                val label = pkg?.let { appLabel(it) } ?: ""
                if (svc == null) {
                    Outcome.Failed("Turn on the “Jev Assistant screen control” accessibility service in settings to let me operate apps.")
                } else if (pkg != null && prefs.isAgentBlocked(pkg, label)) {
                    Outcome.Failed("You've set $label as off-limits for me.")
                } else {
                    svc.startAgent(cmd.goal, pkg)
                    Outcome.Done(if (pkg != null) "Opening $label…" else "On it.", leave = true)
                }
            }
        }
    } catch (e: ActivityNotFoundException) {
        Outcome.Failed("No app on this phone can do that.")
    } catch (e: SecurityException) {
        Outcome.Failed("Android blocked that: a permission is missing. Open Jev Assistant settings.")
    } catch (e: Exception) {
        // Show the real error instead of crashing, so problems are easy to report.
        Outcome.Failed("Something went wrong: ${e.javaClass.simpleName}: ${e.message}")
    }

    private fun start(i: Intent) {
        activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun appLabel(pkg: String): String = try {
        val pm = activity.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun whatsAppPackage(): String? {
        val pm = activity.packageManager
        return listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull {
            try {
                pm.getPackageInfo(it, 0); true
            } catch (e: Exception) {
                false
            }
        }
    }

    /** WhatsApp's official click-to-chat link, opened directly in the app. */
    private fun openWhatsApp(pkg: String, waNumber: String, text: String?) {
        var url = "https://api.whatsapp.com/send?phone=$waNumber"
        if (text != null) url += "&text=" + Uri.encode(text)
        start(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg))
    }

    private fun addToCalendar(
        title: String,
        start: ZonedDateTime?,
        allDayDate: LocalDate?,
        minutes: Int,
        alert: Int?,
        isReminder: Boolean,
    ): Outcome {
        val repo = CalendarRepo(activity)
        val cal = repo.writableCalendar()
        val haveTime = start != null || allDayDate != null
        if (cal != null && haveTime) {
            val id = repo.insert(cal, title, start, allDayDate, minutes, alert)
            if (id != null) {
                val whenText = when {
                    allDayDate != null -> describeDay(allDayDate)
                    else -> describeDay(start!!.toLocalDate()) + " at " + start.format(timeFmt)
                }
                return Outcome.Done(
                    if (isReminder) "Okay, I'll remind you $whenText: $title. (Saved in ${cal.name})"
                    else "Added “$title” $whenText to ${cal.name}" +
                        (if (cal.account.isNotBlank() && cal.account != cal.name) " (${cal.account})." else ".")
                )
            }
        }
        // No calendar permission, no writable calendar, or no time given: open the calendar app pre-filled.
        val i = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
        if (allDayDate != null) {
            val ms = allDayDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            i.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ms)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, ms + 86_400_000L)
        } else if (start != null) {
            val ms = start.toInstant().toEpochMilli()
            i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ms)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, ms + minutes * 60_000L)
        }
        start(i)
        return Outcome.Done(if (haveTime) "Opening your calendar." else "When? I've opened your calendar to set it.", leave = true)
    }

    private fun agenda(date: LocalDate): Outcome {
        val repo = CalendarRepo(activity)
        if (!repo.canRead()) return Outcome.Failed("I need the Calendar permission. Open Jev Assistant settings.")
        val items = repo.eventsOn(date)
        val day = describeDay(date)
        if (items.isEmpty()) return Outcome.Done("Nothing on your calendar $day.")
        val list = items.joinToString("; ") {
            if (it.allDay) "${it.title} (all day)" else "${it.begin.format(timeFmt)} ${it.title}"
        }
        val count = if (items.size == 1) "1 event" else "${items.size} events"
        return Outcome.Done("You have $count $day: $list.")
    }

    private fun describeDay(d: LocalDate): String {
        val today = LocalDate.now()
        return when (d) {
            today -> "today"
            today.plusDays(1) -> "tomorrow"
            else -> "on " + d.format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))
        }
    }
}
