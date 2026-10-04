# Jev Assistant

A fast "do this, do that" voice assistant for Android, in the spirit of the legacy Google Assistant.

1. Your phone's own speech recognizer turns your voice into text (on-device when available).
2. **One** call to TypeSafe's Jev answers every question at once: what you want, which contact, which part of the sentence is the message, which day/hour/minute, and so on.
3. Plain code does the rest: date math, picking the phone number, and launching the clock / dialer / WhatsApp / calendar / music app.

If Jev isn't confident, the assistant asks "Did you mean…?" instead of guessing.

## Commands

| Area | Examples |
|---|---|
| Timers & alarms | "set a timer for 10 minutes", "stop the timer", "wake me up at 6:45", "show my alarms" |
| Calls & WhatsApp | "call mom", "call Alex on his work number", "WhatsApp Sam I'm running late" / "message Sam …" (asks "Send it?" first) |
| Music & media | "play Daft Punk on Spotify", "pause", "next song", "volume up" |
| Reminders & calendar | "remind me to call the bank tomorrow at 10" (a 5-minute calendar event), "add dentist Friday at 3 to my calendar", "what's on my calendar tomorrow" |
| Other | "what time is it"; questions open a web search |
| Operate any app (agent) | "turn on battery saver", "in Instagram like the latest post from NASA", "set the alarm sound in Clock settings" — off by default, see below |

Reminders are saved as calendar events with an alert, so they sync with your Google Calendar.

## Build it (GitHub Actions, no Android Studio needed)

1. Create a **private** repository on GitHub.
2. Upload everything in this folder. On github.com: *Add file → Upload files*, then drag the contents in.
   - Hidden folders like `.github` are often skipped by drag-and-drop. If `.github/workflows/build.yml`
     doesn't appear in the repo afterwards, use *Add file → Create new file*, type the name
     `.github/workflows/build.yml`, and paste that file's contents.
3. Open the **Actions** tab. The "Build APK" run starts on every push (or press *Run workflow*). It takes about 3–5 minutes.
4. When it's green, open the repo's **Releases** page on your phone and download `JevAssistant.apk`.
   Allow your browser to install unknown apps when Android asks.

Every later push builds a new version that installs over the old one, keeping your settings.

## Set it up on the phone

1. Open **Jev Assistant**, paste your key from console.typesafe.ai, and tap **Save key**.
   It is encrypted with a key held in the phone's Keystore and never leaves the device except in the
   `Authorization` header to `api.typesafe.ai`.
2. Tap **Test** to check the connection.
3. Tap **Grant permissions**. Grant only what you want to use.
4. Tap **Open default apps settings → Digital assistant app → Jev Assistant**.

## Home-screen widget

Long-press your home screen → **Widgets** → **Jev** → drag the round mic onto the home screen.
Tapping it opens the assistant already listening. It's the battery-free alternative to "Hey Jev",
which you can leave off (settings section 5).

## "Hey Jev" from any screen

Settings → section 5:

1. **Download speech model** (about 40 MB, one time). It's Vosk's small English model and runs fully offline.
2. **Allow "Display over other apps"**. Android requires this before an app can open itself from the background.
3. Switch on **Listen for the wake phrase**. A silent notification and the mic indicator show while it listens.

The default phrase is **"hey jeff"**. The offline model only knows real English words, "jev" isn't one, and "jeff" sounds the same.
Say the phrase, wait for the beep, then say your command. It won't open over the lock screen.
After a phone restart, tap the "turn Hey Jev back on" notification, because Android doesn't allow background mic listening to start at boot.

## WhatsApp

WhatsApp has no public API for sending from your own account. So after you confirm, the assistant opens the chat with your message already typed.
To have it press **Send** for you, enable the optional **"Jev Assistant screen control"** accessibility service (settings, section 6; the same service also powers agent mode). It only acts for 10 s after a confirmation, only in WhatsApp, and only when the text box holds exactly the confirmed message.
On Android 13+ the switch may be greyed out for sideloaded apps. If so: Settings → Apps → Jev Assistant → ⋮ → **Allow restricted settings**.

## Agent mode: operating any app (experimental, off by default)

When a request isn't one of the built-in commands, Jev can carry it out inside an app,
e.g. "turn on battery saver" or "in Spotify play my Discover Weekly".

**Jev is text-only, so it never sees screenshots.** Android's accessibility system hands over the current
screen as text: every button, field, switch and label, with its role and rough position. Each step is
**one Jev call** that answers three questions at once:
- **action**: which single thing to do next (tap an element, type, scroll, press Enter, go back, done, or stuck);
- **risky**: which element on screen, if tapped, would send, pay, buy, book, post or delete;
- **type_text**: which part of your sentence to type, if typing is needed (Jev picks it; it never invents text).

Code performs the action, waits for the app to settle, and repeats, for at most 15 steps.

Turn it on in settings section 7. It needs the **"Jev Assistant screen control"** accessibility service, the same
one WhatsApp send uses.

Safeguards:
- **Before any risky tap it asks you.** "Risky" means Jev rated it so, or its label contains words like send, pay, buy,
  order, book, delete or post.
- **When Jev isn't sure what to do next**, it shows you the top options and you choose.
- **A Stop button** sits at the top of the screen the whole time.
- **Password fields are never read.**
- **Blocked apps:** list apps it must never operate (e.g. banking). The text of screens it operates on goes to TypeSafe's servers.
- **"Ask before every tap"** is an optional switch for when you're first trying it.
- **It stops on its own** if the screen stops changing or after 15 steps.

Limits: short, concrete goals work best, because TypeSafe notes Jev is weaker at multi-step reasoning.
Games and apps that draw their own UI without accessibility labels are mostly invisible to it.

## Reminders

"Remind me to … at/tomorrow/in 20 minutes" adds a **5-minute calendar event** with an alert when it starts,
in your Google account's calendar. The length is adjustable in settings section 8.
Without a time, your calendar app opens with the title filled in so you can pick one.

## Tuning

- **Confidence threshold** (settings): raise it if it acts on wrong guesses; lower it if it asks too often.
- **Show Jev details** shows the chosen intent, confidences, and latency under each reply, which is
  handy for seeing what Jev understood.
- All the questions Jev answers are in `Interpreter.kt` → `buildQuestions()`. Wording changes there
  are the main lever for accuracy (Jev reads instructions very literally).

## Project layout

```
app/src/main/java/dev/jevassist/
  AssistActivity.kt  overlay UI, speech in/out, confirm flow
  Interpreter.kt     the Jev questions (one fan-out call) + answers -> Command
  TimeResolver.kt    date/time math from Jev's date/time parts (pure code)
  TextTools.kt       span candidates, durations, number words, phone numbers
  Actions.kt         runs commands: clock, dialer, WhatsApp, media keys, calendar
  Repos.kt           contacts, calendar, music apps
  JevClient.kt       HTTP client for POST /v1/systemone
  AppPrefs.kt        settings + encrypted API key
  MainActivity.kt    settings screen
  WakeWordService.kt "Hey Jev" offline listener (Vosk) + model download
  BootReceiver.kt    "turn Hey Jev back on" notification after reboot
  AssistWidget.kt    home-screen mic widget
  JevAccessibilityService.kt  screen control: agent overlay (status, questions, Stop) + WhatsApp send
  ScreenAgent.kt     reads the screen as text, one Jev call per step, performs the action
```

## Known limits

- Jev runs in the cloud, so each command needs a connection (~0.1–0.5 s for Jev plus network).
- The wake word costs some battery, since the mic is processed continuously (on-device).
- If the build ever fails resolving `vosk-android:0.3.75` or `jna:5.18.1`, change them in `app/build.gradle.kts`
  to `0.3.47` and `5.13.0`.
- The phone's power-button long-press may stay tied to Gemini on some phones (e.g. Pixels).
  The corner-swipe / home-hold gesture follows the default assistant setting.
- English phrasing works best: the questions to Jev are written in English.
