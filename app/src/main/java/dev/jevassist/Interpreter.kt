package dev.jevassist

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.view.KeyEvent
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** What the assistant will do. Built in code from Jev's typed answers. */
sealed class Command {
    data class Timer(val seconds: Int) : Command()
    object StopTimer : Command()
    data class Alarm(val hour: Int, val minute: Int) : Command()
    object ShowAlarms : Command()
    data class Call(val name: String, val number: String) : Command()
    data class Text(val name: String, val number: String, val body: String?) : Command()
    data class PlayMedia(val query: String, val pkg: String?, val appLabel: String?) : Command()
    data class MediaKey(val keyCode: Int, val say: String) : Command()
    data class Volume(val up: Boolean) : Command()
    data class Reminder(val title: String, val at: ZonedDateTime?) : Command()
    data class Event(
        val title: String,
        val start: ZonedDateTime?,
        val allDayDate: LocalDate?,
        val minutes: Int,
    ) : Command()
    data class Agenda(val date: LocalDate) : Command()
    object TellTime : Command()
    data class WebSearch(val query: String) : Command()
}

sealed class Resolution {
    data class Run(val command: Command) : Resolution()
    /** Jev wasn't confident: offer the top options as buttons (key, label). */
    data class Clarify(
        val questionId: String,
        val prompt: String,
        val options: List<Pair<String, String>>,
    ) : Resolution()
    data class Fail(val message: String, val offerSearch: Boolean = false) : Resolution()
}

/** One utterance + Jev's answers + the candidate lists the answers refer to. */
class Analysis(
    val utterance: String,
    val answers: JevAnswers,
    val spans: Map<String, String>,
    val contacts: Map<String, Contact>,
    val spokenNumber: String?,
    val musicApps: Map<String, MediaApp>,
    val now: ZonedDateTime,
) {
    /** Choices the user made by tapping "Did you mean…" buttons override Jev's picks. */
    val overrides = HashMap<String, String>()

    fun pick(id: String): String? = overrides[id] ?: answers.choice(id)?.choice
    val intent: String? get() = pick("intent")
}

object Intents {
    // key -> (button label, description Jev sees)
    val DEFS: LinkedHashMap<String, Pair<String, String>> = linkedMapOf(
        "set_timer" to ("Set a timer" to "Start a countdown timer for a length of time, e.g. 'set a timer for 10 minutes', 'timer 5 minutes'."),
        "stop_timer" to ("Stop the timer" to "Stop, cancel, or dismiss a timer that is running or ringing."),
        "set_alarm" to ("Set an alarm" to "Set an alarm that rings at a clock time, e.g. 'wake me up at 7', 'set an alarm for 6:30'."),
        "show_alarms" to ("Show alarms" to "See, list, cancel, turn off, or manage existing alarms."),
        "call" to ("Call someone" to "Make a phone call to a person or a phone number."),
        "text" to ("Send a text" to "Send a text message / SMS to a person."),
        "play_media" to ("Play something" to "Play a specific song, artist, album, playlist, podcast, radio station, or genre."),
        "resume_media" to ("Resume playback" to "Start or resume playback without naming anything, e.g. 'play', 'resume', 'play music', 'continue'."),
        "pause_media" to ("Pause" to "Pause or stop the music, podcast, or video that is playing."),
        "next_track" to ("Next track" to "Skip to the next song or track."),
        "previous_track" to ("Previous track" to "Go back to the previous song or track, or restart the current one."),
        "volume_up" to ("Volume up" to "Make it louder / turn the volume up."),
        "volume_down" to ("Volume down" to "Make it quieter / turn the volume down."),
        "reminder" to ("Create a reminder" to "Create a reminder, e.g. 'remind me to buy milk at 5', 'remind me tomorrow to call the bank'."),
        "event" to ("Add a calendar event" to "Add an event, appointment, or meeting to the calendar, e.g. 'add dentist on Friday at 3 to my calendar'."),
        "agenda" to ("Check the calendar" to "Ask what is on the calendar or schedule, e.g. 'what's on my calendar tomorrow', 'do I have meetings today'."),
        "time" to ("Tell the time" to "Ask what time it is or what today's date is."),
        "web_search" to ("Search the web" to "Ask a question or look something up: facts, weather, news, definitions, sports scores."),
        "other" to ("Something else" to "Anything else that is not one of the requests above, including chit-chat or unclear speech."),
    )

    fun label(key: String): String? = DEFS[key]?.first
}

internal val MONTHS = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)
internal val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

class Interpreter(private val ctx: Context, private val prefs: AppPrefs) {

    private val contactsRepo = ContactsRepo(ctx)

    // ---------------------------------------------------------------- the Jev call

    /** Blocking. Builds candidates in code, asks every question in ONE Jev call. */
    fun analyze(utterance: String): Analysis {
        val key = prefs.apiKey
            ?: throw JevException("No Jev API key yet. Add it in Jev Assistant settings.")
        val client = JevClient(key, prefs.model)

        val spans = LinkedHashMap<String, String>()
        TextTools.spanCandidates(TextTools.words(utterance)).forEachIndexed { i, s -> spans["s$i"] = s }

        val contacts = LinkedHashMap<String, Contact>()
        if (contactsRepo.hasPermission()) {
            contactsRepo.candidates(utterance).forEachIndexed { i, c -> contacts["c$i"] = c }
        }
        val number = TextTools.findPhoneNumber(utterance)

        val apps = LinkedHashMap<String, MediaApp>()
        MediaApps.list(ctx).take(40).forEachIndexed { i, a -> apps["a$i"] = a }

        val questions = buildQuestions(spans, contacts, number, apps)
        val state = JSONObject().put("request", utterance)
        val answers = client.ask(state, questions)
        return Analysis(utterance, answers, spans, contacts, number, apps, ZonedDateTime.now())
    }

    /** Classifies a spoken reply to "Send it?" as yes / no / unclear. */
    fun classifyReply(prompt: String, reply: String): String {
        val key = prefs.apiKey ?: throw JevException("No Jev API key.")
        val q = JSONObject().put(
            "confirm",
            choice(
                "Does the user's `reply` agree to what the assistant asked in `assistant_asked`?",
                linkedMapOf(
                    "yes" to "Agrees or confirms, e.g. 'yes', 'send it', 'sure', 'ok', 'go ahead'.",
                    "no" to "Declines or cancels, e.g. 'no', 'cancel', 'don't', 'stop', 'never mind'.",
                    "unclear" to "Neither agrees nor declines, or is about something else.",
                ),
            ),
        )
        val state = JSONObject().put("assistant_asked", prompt).put("reply", reply)
        val ans = JevClient(key, prefs.model).ask(state, q).choice("confirm")
        return if (ans == null || ans.confidence < prefs.confidenceThreshold) "unclear" else ans.choice
    }

    private fun choice(instructions: String, criteria: Map<String, String?>): JSONObject {
        val c = JSONObject()
        for ((k, v) in criteria) c.put(k, v ?: JSONObject.NULL)
        return JSONObject().put("type", "choice").put("instructions", instructions).put("criteria", c)
    }

    private fun buildQuestions(
        spans: Map<String, String>,
        contacts: Map<String, Contact>,
        spokenNumber: String?,
        apps: Map<String, MediaApp>,
    ): JSONObject {
        val q = JSONObject()

        q.put("intent", choice(
            "`request` is a voice command spoken to a phone assistant. What does the user want the assistant to do?",
            Intents.DEFS.mapValues { it.value.second },
        ))

        // Free text (message body / song / reminder title) as a choice over spans of the sentence.
        val payload = LinkedHashMap<String, String?>()
        spans.forEach { (k, v) -> payload[k] = v }
        payload["none"] = "The request contains no such content."
        q.put("payload", choice(
            "Which option is exactly the content of the command in `request`: the text message to send, " +
                "or the song / artist / album / playlist / podcast to play, or what the reminder or calendar event is about? " +
                "The option must not include command words (like 'text', 'tell', 'play', 'remind me to', 'add'), " +
                "the person's name, the app name, or date and time words.",
            payload,
        ))

        if (contacts.isNotEmpty() || spokenNumber != null) {
            val crit = LinkedHashMap<String, String?>()
            contacts.forEach { (k, c) -> crit[k] = c.name }
            if (spokenNumber != null) crit["num"] = "The phone number $spokenNumber, said directly"
            crit["none"] = "None of these people is mentioned in the request."
            q.put("contact", choice("Which person (or number) does the user want to call or send a message to?", crit))
            q.put("number_type", choice(
                "If the request says which of the person's phone numbers to use, which one?",
                linkedMapOf(
                    "mobile" to "Mobile or cell", "home" to "Home", "work" to "Work or office",
                    "not_stated" to "The request doesn't say which number.",
                ),
            ))
        }

        if (apps.isNotEmpty()) {
            val crit = LinkedHashMap<String, String?>()
            apps.forEach { (k, a) -> crit[k] = a.label }
            crit["not_stated"] = "The request does not name an app."
            q.put("music_app", choice("Which app does the request say to play it in?", crit))
        }

        // Date parts (pattern from TypeSafe's date-extraction cookbook: Jev reads parts, code does calendar math).
        val role = "the day the request is about (for the alarm, reminder, event, or calendar question)"
        val absent = "The request does not state this."
        q.put("date_mode", choice(
            "How does the request state $role? 'absolute' = a calendar date naming a month (e.g. 'October 14', " +
                "'the 3rd of March'); 'relative' = relative to today (today, tonight, tomorrow, the day after tomorrow, " +
                "or a weekday such as 'next Thursday'); 'none' = no day is stated.",
            linkedMapOf("absolute" to null, "relative" to null, "none" to null),
        ))
        q.put("month", choice("If $role is an absolute calendar date, which month?",
            MONTHS.associateWith { null as String? } + ("none" to absent)))
        q.put("day", choice("If $role is an absolute calendar date, which day of the month (1-31)?",
            (1..31).associate { it.toString() to null as String? } + ("none" to absent)))
        q.put("day_anchor", choice(
            "If $role is relative to today, which day? 'today' (including 'tonight' / 'this evening'), 'tomorrow', " +
                "'day_after' (the day after tomorrow), or 'weekday' (a named day of the week).",
            linkedMapOf("today" to null, "tomorrow" to null, "day_after" to null, "weekday" to null, "none" to absent),
        ))
        q.put("weekday", choice("If $role names a day of the week, which one?",
            WEEKDAYS.associateWith { null as String? } + ("none" to absent)))
        q.put("week_offset", choice(
            "If $role names a weekday, which week? 'next' for 'next Thursday' or 'Thursday next week'; " +
                "'current' for 'this Thursday'; 'none' for a bare weekday with no qualifier.",
            linkedMapOf("current" to null, "next" to null, "none" to absent),
        ))

        // Time parts.
        q.put("time_mode", choice(
            "How does the request state the time?",
            linkedMapOf(
                "clock" to "A clock time, e.g. 'at 5', '7:30 pm', 'at noon', 'at 17:00', 'half past six'.",
                "in_duration" to "A time counted from now, e.g. 'in 20 minutes', 'in 2 hours'.",
                "none" to "No time is stated (a timer's length does not count as a time).",
            ),
        ))
        q.put("hour", choice(
            "If the request states a clock time, which hour? Answer the hour of the clock time: '5:30' or 'half past five' -> 5; " +
                "'quarter to six' -> 5; 'noon' -> 12; 'midnight' -> 0; '17:30' -> 17.",
            (0..23).associate { it.toString() to null as String? } + ("none" to "No clock time is stated."),
        ))
        q.put("minute", choice(
            "If the request states a clock time, which minute? '7:30' or 'half past seven' -> 30; 'quarter past' -> 15; " +
                "'quarter to' -> 45; '7 o'clock' or just '7' -> 0.",
            (0..59).associate { it.toString() to null as String? } + ("none" to "No clock time is stated."),
        ))
        q.put("period", choice(
            "If the request states a clock time, is it in the morning or the afternoon/evening?",
            linkedMapOf(
                "am" to "Morning: 'a.m.', 'in the morning', or waking up.",
                "pm" to "Afternoon, evening, or night: 'p.m.', 'tonight', 'this evening', 'in the afternoon'.",
                "not_stated" to "The request doesn't say or imply which.",
            ),
        ))
        q.put("event_length", choice(
            "If the request says how long the event lasts, how long?",
            linkedMapOf(
                "15m" to "15 minutes", "30m" to "30 minutes / half an hour", "45m" to "45 minutes",
                "1h" to "1 hour", "90m" to "an hour and a half", "2h" to "2 hours", "3h" to "3 hours or more",
                "all_day" to "All day", "not_stated" to "The length isn't stated.",
            ),
        ))
        return q
    }

    // ------------------------------------------------- answers -> command (plain code)

    /** Blocking (may read contacts). */
    fun resolve(a: Analysis): Resolution {
        if (a.answers.choice("intent") == null) return Resolution.Fail("Jev didn't return an answer.")
        clarify(a, "intent", "Did you mean…") { Intents.label(it) }?.let { return it }

        return when (val intent = a.intent ?: "other") {
            "set_timer" -> {
                val s = TextTools.parseDurationSeconds(a.utterance)
                    ?: return Resolution.Fail("For how long? Try “set a timer for 5 minutes”.")
                Resolution.Run(Command.Timer(s))
            }
            "stop_timer" -> Resolution.Run(Command.StopTimer)
            "set_alarm" -> resolveAlarm(a)
            "show_alarms" -> Resolution.Run(Command.ShowAlarms)
            "call", "text" -> resolveCallOrText(a, intent)
            "play_media" -> {
                val query = payload(a)
                    ?: return Resolution.Run(Command.MediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, "Playing."))
                val app = a.musicApps[a.pick("music_app")]
                    ?: a.musicApps.values.firstOrNull { it.pkg == prefs.lastMusicPackage }
                Resolution.Run(Command.PlayMedia(query, app?.pkg, app?.label))
            }
            "resume_media" -> Resolution.Run(Command.MediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, "Playing."))
            "pause_media" -> Resolution.Run(Command.MediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE, "Paused."))
            "next_track" -> Resolution.Run(Command.MediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "Next track."))
            "previous_track" -> Resolution.Run(Command.MediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Previous track."))
            "volume_up" -> Resolution.Run(Command.Volume(up = true))
            "volume_down" -> Resolution.Run(Command.Volume(up = false))
            "reminder" -> {
                val title = payload(a) ?: return Resolution.Fail("What should I remind you about?")
                val (start, dateOnly) = resolveStart(a)
                val at = start ?: dateOnly?.let { ZonedDateTime.of(it, LocalTime.of(9, 0), a.now.zone) }
                Resolution.Run(Command.Reminder(title, at))
            }
            "event" -> {
                val title = payload(a) ?: return Resolution.Fail("What's the event called?")
                val (start, dateOnly) = resolveStart(a)
                val len = a.pick("event_length")
                if (len == "all_day" && (start != null || dateOnly != null)) {
                    Resolution.Run(Command.Event(title, null, dateOnly ?: start!!.toLocalDate(), 0))
                } else if (start != null) {
                    Resolution.Run(Command.Event(title, start, null, lengthMinutes(len)))
                } else {
                    Resolution.Run(Command.Event(title, null, dateOnly, 0))
                }
            }
            "agenda" -> Resolution.Run(Command.Agenda(resolveDate(a) ?: a.now.toLocalDate()))
            "time" -> Resolution.Run(Command.TellTime)
            "web_search" -> Resolution.Run(Command.WebSearch(a.utterance))
            else -> Resolution.Fail("Sorry, I can't do that yet.", offerSearch = true)
        }
    }

    /** Confidence-gated routing: ask instead of acting when Jev is unsure. */
    private fun clarify(a: Analysis, id: String, prompt: String, label: (String) -> String?): Resolution.Clarify? {
        if (a.overrides.containsKey(id)) return null
        val ans = a.answers.choice(id) ?: return null
        if (ans.confidence >= prefs.confidenceThreshold) return null
        val options = ans.top(3)
            .filter { it.second >= 0.08 && it.first != "none" }
            .mapNotNull { (k, _) -> label(k)?.let { k to it } }
        return if (options.size >= 2) Resolution.Clarify(id, prompt, options) else null
    }

    private fun payload(a: Analysis): String? {
        val k = a.pick("payload") ?: return null
        return a.spans[k]?.takeIf { it.isNotBlank() }
    }

    private fun resolveCallOrText(a: Analysis, intent: String): Resolution {
        if (a.contacts.isEmpty() && a.spokenNumber == null) {
            return Resolution.Fail(
                if (!contactsRepo.hasPermission()) "I need the Contacts permission. Open Jev Assistant settings."
                else "I couldn't find that person in your contacts."
            )
        }
        clarify(a, "contact", "Who do you mean?") { k ->
            a.contacts[k]?.name ?: if (k == "num") a.spokenNumber else null
        }?.let { return it }

        val name: String
        val number: String
        when (val k = a.pick("contact")) {
            null, "none" -> return Resolution.Fail("I couldn't find that person in your contacts.")
            "num" -> {
                number = a.spokenNumber ?: return Resolution.Fail("I didn't catch the number.")
                name = number
            }
            else -> {
                val c = a.contacts[k] ?: return Resolution.Fail("I couldn't find that person in your contacts.")
                val nums = contactsRepo.numbers(c.id)
                if (nums.isEmpty()) return Resolution.Fail("${c.name} has no phone number.")
                name = c.name
                number = pickNumber(nums, a.pick("number_type")).number
            }
        }
        return if (intent == "call") Resolution.Run(Command.Call(name, number))
        else Resolution.Run(Command.Text(name, number, payload(a)))
    }

    private fun pickNumber(nums: List<PhoneNumber>, type: String?): PhoneNumber {
        val wanted = when (type) {
            "mobile" -> Phone.TYPE_MOBILE
            "home" -> Phone.TYPE_HOME
            "work" -> Phone.TYPE_WORK
            else -> null
        }
        return wanted?.let { w -> nums.firstOrNull { it.type == w } }
            ?: nums.firstOrNull { it.primary }
            ?: nums.firstOrNull { it.type == Phone.TYPE_MOBILE }
            ?: nums.first()
    }

    private fun resolveAlarm(a: Analysis): Resolution {
        val t = TimeResolver(a.now, a.utterance) { a.pick(it) }.alarmTime()
            ?: return Resolution.Fail("What time? Try “set an alarm for 7 AM”.")
        return Resolution.Run(Command.Alarm(t.hour, t.minute))
    }

    private fun resolveStart(a: Analysis): Pair<ZonedDateTime?, LocalDate?> =
        TimeResolver(a.now, a.utterance) { a.pick(it) }.start()

    private fun resolveDate(a: Analysis): LocalDate? =
        TimeResolver(a.now, a.utterance) { a.pick(it) }.date()

    private fun lengthMinutes(key: String?): Int = when (key) {
        "15m" -> 15
        "30m" -> 30
        "45m" -> 45
        "90m" -> 90
        "2h" -> 120
        "3h" -> 180
        else -> 60
    }
}
