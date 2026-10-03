# Jev Assistant

A fast "do this, do that" voice assistant for Android, in the spirit of the legacy Google Assistant.

1. Your phone's own speech recognizer turns your voice into text (on-device when available).
2. **One** call to TypeSafe's Jev answers every question at once: what you want, which contact, which part of the sentence is the message, which day/hour/minute, and so on.
3. Plain code does the rest: date math, picking the phone number, and launching the clock / dialer / SMS / calendar / music app.

If Jev isn't confident, the assistant asks "Did you mean…?" instead of guessing.

## Commands (v1)

| Area | Examples |
|---|---|
| Timers & alarms | "set a timer for 10 minutes", "stop the timer", "wake me up at 6:45", "show my alarms" |
| Calls & texts | "call mom", "call Alex on his work number", "text Sam I'm running late" (asks "Send it?" first) |
| Music & media | "play Daft Punk on Spotify", "pause", "next song", "volume up" |
| Reminders & calendar | "remind me to call the bank tomorrow at 10", "add dentist Friday at 3 to my calendar", "what's on my calendar tomorrow" |
| Other | "what time is it"; questions open a web search |

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
  Actions.kt         runs commands: clock, dialer, SMS, media keys, calendar
  Repos.kt           contacts, calendar, music apps
  JevClient.kt       HTTP client for POST /v1/systemone
  AppPrefs.kt        settings + encrypted API key
  MainActivity.kt    settings screen
```

## Known limits

- Jev runs in the cloud, so each command needs a connection (~0.1–0.5 s for Jev plus network).
- No wake word yet: open it with your assistant gesture or the app.
- The phone's power-button long-press may stay tied to Gemini on some phones (e.g. Pixels).
  The corner-swipe / home-hold gesture follows the default assistant setting.
- English phrasing works best: the questions to Jev are written in English.
