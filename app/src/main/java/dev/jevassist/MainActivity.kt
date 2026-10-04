package dev.jevassist

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var prefs: AppPrefs
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var keyStatus: TextView
    private lateinit var permStatus: TextView
    private lateinit var roleStatus: TextView
    private lateinit var testResult: TextView

    private val permissions: Array<String> = listOfNotNull(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null,
    ).toTypedArray()

    private lateinit var calStatus: TextView
    private lateinit var wakeStatus: TextView
    private lateinit var wakeSwitch: Switch
    private lateinit var waStatus: TextView
    private lateinit var agentStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        title = "Jev Assistant"

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(col) })

        col.addView(text("A fast voice assistant: your phone turns speech into text, Jev decides what you meant, and plain code does it.", 15f))

        // 1. API key
        col.addView(header("1. Jev API key"))
        val keyInput = EditText(this).apply {
            hint = "Paste your key from console.typesafe.ai"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        col.addView(keyInput)
        keyStatus = text("", 13f)
        col.addView(row(
            button("Save key") {
                prefs.apiKey = keyInput.text.toString()
                keyInput.text = null
                refresh()
            },
            button("Remove") { prefs.apiKey = null; refresh() },
        ))
        col.addView(keyStatus)
        testResult = text("", 13f).apply { typeface = Typeface.MONOSPACE }
        col.addView(button("Test with “remind me to buy milk tomorrow at 5”") { runTest() })
        col.addView(testResult)

        // 2. Permissions
        col.addView(header("2. Permissions"))
        col.addView(text("Microphone for listening; contacts and phone for calls and WhatsApp; calendar for reminders and events; notifications for the Hey Jev indicator. Grant only what you want to use.", 13f))
        col.addView(button("Grant permissions") { requestPermissions(permissions, 1) })
        permStatus = text("", 13f)
        col.addView(permStatus)
        calStatus = text("", 13f)
        col.addView(calStatus)

        // 3. Default assistant
        col.addView(header("3. Make it your assistant"))
        col.addView(text("Open Default apps → Digital assistant app → choose Jev Assistant. Then your assistant gesture (swipe from a bottom corner, hold Home, or the side key on some phones) opens it instead of Gemini.", 13f))
        col.addView(button("Open default apps settings") { openDefaultApps() })
        roleStatus = text("", 13f)
        col.addView(roleStatus)

        // 4. Options
        col.addView(header("4. Options"))
        col.addView(Switch(this).apply {
            text = "Speak responses out loud"
            isChecked = prefs.speakResponses
            setOnCheckedChangeListener { _, v -> prefs.speakResponses = v }
        })
        col.addView(Switch(this).apply {
            text = "Show Jev details (intent, confidence, latency)"
            isChecked = prefs.showDebug
            setOnCheckedChangeListener { _, v -> prefs.showDebug = v }
        })
        col.addView(Switch(this).apply {
            text = "Close the assistant by itself after answering"
            isChecked = prefs.autoClose
            setOnCheckedChangeListener { _, v -> prefs.autoClose = v }
        })
        col.addView(Switch(this).apply {
            text = "Keep a log of everything (on this phone only)"
            isChecked = prefs.keepLogs
            setOnCheckedChangeListener { _, v -> prefs.keepLogs = v }
        })
        col.addView(button("View logs") { startActivity(Intent(this, LogActivity::class.java)) })
        val thrLabel = text("", 14f)
        fun showThr() {
            thrLabel.text = "Ask “Did you mean…?” when Jev's confidence is below " +
                "%.2f".format(prefs.confidenceThreshold)
        }
        showThr()
        col.addView(thrLabel)
        col.addView(SeekBar(this).apply {
            max = 60 // 0.30 .. 0.90
            progress = ((prefs.confidenceThreshold - 0.30f) * 100).roundToInt().coerceIn(0, 60)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    prefs.confidenceThreshold = 0.30f + p / 100f
                    showThr()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        val modelInput = EditText(this).apply {
            setText(prefs.model)
            setSingleLine(true)
            hint = "jev-latest"
        }
        col.addView(text("Model", 14f))
        col.addView(row(modelInput, button("Save") { prefs.model = modelInput.text.toString(); refresh() }))

        // 5. Hey Jev
        col.addView(header("5. “Hey Jev” from any screen"))
        col.addView(text("Listens offline, on the phone, for your wake phrase. Nothing is sent anywhere until " +
            "the assistant opens. Uses some battery, and Android shows a microphone indicator while it's on. " +
            "It won't open over the lock screen.", 13f))
        val phraseInput = EditText(this).apply {
            setText(prefs.wakePhrase)
            setSingleLine(true)
        }
        col.addView(text("Wake phrase (ordinary English words; “hey jeff” sounds like “hey jev”)", 14f))
        col.addView(row(phraseInput, button("Save") {
            prefs.wakePhrase = phraseInput.text.toString()
            phraseInput.setText(prefs.wakePhrase)
            restartWakeIfOn()
        }))
        col.addView(row(
            button("Download speech model (40 MB)") { downloadModel() },
        ))
        col.addView(button("Allow “Display over other apps”") {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
        })
        wakeSwitch = Switch(this).apply {
            text = "Listen for the wake phrase"
            isChecked = prefs.wakeEnabled
            setOnCheckedChangeListener { _, on -> setWake(on) }
        }
        col.addView(wakeSwitch)
        wakeStatus = text("", 13f)
        col.addView(wakeStatus)

        // 6. WhatsApp
        col.addView(header("6. WhatsApp auto-send"))
        col.addView(text("WhatsApp has no public API for sending from your own account, so the assistant opens the " +
            "chat with your message typed in. To have it tap Send for you after you confirm, turn on the " +
            "“Jev Assistant screen control” accessibility service (one service powers both send and agent mode). It only acts for 10 seconds after you confirm, " +
            "only in WhatsApp, and only if the text box holds exactly the confirmed message.", 13f))
        col.addView(text("If the switch is greyed out: Settings → Apps → Jev Assistant → ⋮ → “Allow restricted settings”, then try again.", 13f))
        col.addView(button("Open accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(Switch(this).apply {
            text = "Tap Send automatically after I confirm"
            isChecked = prefs.whatsappAutoSend
            setOnCheckedChangeListener { _, v -> prefs.whatsappAutoSend = v; refresh() }
        })
        waStatus = text("", 13f)
        col.addView(waStatus)

        // 7. Agent mode
        col.addView(header("7. Let Jev operate any app (experimental)"))
        col.addView(text("When a request isn't one of the built-in commands, Jev can carry it out inside an app: " +
            "“turn on battery saver”, “in Spotify play my Discover Weekly”. Jev is text-only, so instead of " +
            "screenshots it gets the screen as a list of buttons, fields and labels from Android's accessibility " +
            "system, and picks one action per step. Short, concrete requests work best.", 13f))
        col.addView(text("Safety: before any tap that looks like it sends, pays, buys, books, posts or deletes, it asks you " +
            "first. Password fields are never read. A Stop button is always on screen. The text of the screens it " +
            "operates on is sent to Jev (TypeSafe's servers), so add private apps (e.g. banking) to the list below. " +
            "Uses the same “screen control” accessibility service as WhatsApp send (section 6).", 13f))
        col.addView(Switch(this).apply {
            text = "Enable agent mode"
            isChecked = prefs.agentEnabled
            setOnCheckedChangeListener { _, v -> prefs.agentEnabled = v; refresh() }
        })
        col.addView(Switch(this).apply {
            text = "Ask before every tap (for trying it out)"
            isChecked = prefs.agentConfirmEveryStep
            setOnCheckedChangeListener { _, v -> prefs.agentConfirmEveryStep = v }
        })
        col.addView(text("Never operate these apps (comma-separated names):", 14f))
        val blockInput = EditText(this).apply {
            setText(prefs.agentBlocklist)
            hint = "e.g. Chase, PayPal, Revolut"
        }
        col.addView(row(blockInput, button("Save") { prefs.agentBlocklist = blockInput.text.toString(); refresh() }))
        agentStatus = text("", 13f)
        col.addView(agentStatus)

        // 8. Reminders
        col.addView(header("8. Reminders"))
        col.addView(text("Reminders are saved as short events in your calendar, with an alert when they start.", 13f))
        val remLen = EditText(this).apply {
            setText(prefs.reminderEventMinutes.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        col.addView(text("Reminder event length (minutes)", 14f))
        col.addView(row(remLen, button("Save") {
            prefs.reminderEventMinutes = remLen.text.toString().toIntOrNull() ?: 5
            remLen.setText(prefs.reminderEventMinutes.toString())
        }))

        col.addView(header("Try it"))
        col.addView(button("Open the assistant") {
            startActivity(Intent(this, AssistActivity::class.java))
        })
        col.addView(text("Examples: “set a timer for 10 minutes” · “wake me up at 6:45” · “call mom” · " +
            "“WhatsApp Alex I'm running late” · “play Daft Punk on Spotify” · “next song” · " +
            "“remind me to call the bank tomorrow at 10” · “add dentist Friday at 3 to my calendar” · " +
            "“what's on my calendar tomorrow”", 13f))
    }

    override fun onResume() {
        super.onResume()
        // Re-start listening if it's switched on but not running (e.g. after a reboot).
        if (prefs.wakeEnabled && WakeWordService.instance == null && wakeReady() == null) {
            try { WakeWordService.start(this) } catch (e: Exception) { WakeWordService.lastError = e.message }
        }
        refresh()
        main.postDelayed({ refresh() }, 1500)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    companion object {
        const val EXTRA_RESTART_WAKE = "restart_wake"
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun refresh() {
        keyStatus.text = if (prefs.hasApiKey) "✓ Key saved (stored encrypted on this phone)" else "No key saved yet"
        permStatus.text = permissions.joinToString("\n") { p ->
            (if (granted(p)) "✓ " else "✗ ") + p.substringAfterLast('.').lowercase().replace('_', ' ')
        }
        calStatus.text = CalendarRepo(this).writableCalendar()?.let {
            "Reminders and events go to: ${it.name}" + (if (it.account.isNotBlank()) " (${it.account})" else "")
        } ?: "Reminders: no writable calendar found (grant Calendar, or the calendar app will open instead)"
        wakeStatus.text = when {
            !WakeModel.isReady(this) -> "✗ Speech model not downloaded yet"
            !Settings.canDrawOverlays(this) -> "✗ “Display over other apps” not allowed yet"
            WakeWordService.lastError != null -> "✗ ${WakeWordService.lastError}"
            prefs.wakeEnabled && WakeWordService.instance != null -> "✓ Listening for “${prefs.wakePhrase}”"
            prefs.wakeEnabled -> "Starting…"
            else -> "Off"
        }
        if (wakeSwitch.isChecked != prefs.wakeEnabled) wakeSwitch.isChecked = prefs.wakeEnabled
        agentStatus.text = when {
            !prefs.agentEnabled -> "Off. Prepared commands still work."
            !JevAccessibilityService.running -> "✗ Turn on the “Jev Assistant screen control” accessibility service (section 6)."
            else -> "✓ Ready. Try: “turn on battery saver”."
        }
        waStatus.text = when {
            !prefs.whatsappAutoSend -> "Auto-send off: you tap Send in WhatsApp yourself."
            JevAccessibilityService.running -> "✓ Auto-send is on"
            else -> "✗ Accessibility service not enabled: you'll tap Send yourself."
        }
        roleStatus.text = if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && rm.isRoleHeld(RoleManager.ROLE_ASSISTANT))
                "✓ Jev Assistant is your default assistant"
            else "✗ Not the default assistant yet"
        } else ""
    }

    /** null = ready, otherwise what's missing. */
    private fun wakeReady(): String? = when {
        !WakeModel.isReady(this) -> "Download the speech model first."
        !granted(Manifest.permission.RECORD_AUDIO) -> "Grant the microphone permission first."
        !Settings.canDrawOverlays(this) -> "Allow “Display over other apps” first."
        else -> null
    }

    private fun setWake(on: Boolean) {
        if (on) {
            val missing = wakeReady()
            if (missing != null) {
                wakeSwitch.isChecked = false
                wakeStatus.text = "✗ $missing"
                return
            }
            prefs.wakeEnabled = true
            WakeWordService.lastError = null
            try {
                WakeWordService.start(this)
            } catch (e: Exception) {
                WakeWordService.lastError = e.message
            }
        } else {
            prefs.wakeEnabled = false
            if (WakeWordService.instance != null) WakeWordService.stop(this)
        }
        main.postDelayed({ refresh() }, 800)
    }

    private fun restartWakeIfOn() {
        if (!prefs.wakeEnabled || WakeWordService.instance == null) return
        WakeWordService.instance?.pause()
        WakeWordService.instance?.resume()
        refresh()
    }

    private fun downloadModel() {
        wakeStatus.text = "Starting download…"
        worker.execute {
            val msg = try {
                WakeModel.download(this) { p -> main.post { wakeStatus.text = p } }
                "✓ Model ready. Now allow “Display over other apps” and switch listening on."
            } catch (e: Exception) {
                "✗ Download failed: ${e.message}"
            }
            main.post { wakeStatus.text = msg }
        }
    }

    private fun openDefaultApps() {
        val attempts = listOf(
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in attempts) {
            try {
                startActivity(i); return
            } catch (e: Exception) {
                // try the next one
            }
        }
    }

    private fun runTest() {
        testResult.text = "Asking Jev…"
        val interpreter = Interpreter(this, prefs)
        worker.execute {
            val msg = try {
                val a = interpreter.analyze("remind me to buy milk tomorrow at 5")
                val intent = a.answers.choice("intent")
                val payload = a.answers.choice("payload")
                val r = interpreter.resolve(a)
                "✓ Connected (${a.answers.model}, ${a.answers.latencyMs} ms, ${a.answers.inputTokens} tokens)\n" +
                    "intent: ${intent?.choice} (${"%.2f".format(intent?.confidence ?: 0.0)})\n" +
                    "title: ${a.spans[payload?.choice] ?: payload?.choice}\n" +
                    "result: ${describe(r)}"
            } catch (e: Exception) {
                "✗ ${e.message}"
            }
            main.post { testResult.text = msg }
        }
    }

    private fun describe(r: Resolution): String = when (r) {
        is Resolution.Run -> r.command.toString()
        is Resolution.Clarify -> "would ask: ${r.prompt} ${r.options.joinToString { it.second }}"
        is Resolution.Fail -> "would say: ${r.message}"
    }

    // ---- tiny view helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun header(s: String) = TextView(this).apply {
        text = s
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(22), 0, dp(6))
    }

    private fun text(s: String, size: Float) = TextView(this).apply {
        text = s
        textSize = size
        setPadding(0, dp(2), 0, dp(4))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: android.view.View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(if (i == 0) 0 else WRAP_CONTENT, WRAP_CONTENT, if (i == 0) 1f else 0f))
        }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }
}
