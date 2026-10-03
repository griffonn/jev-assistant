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

    private val permissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

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
        col.addView(text("Microphone for listening; contacts, phone and SMS for calls and texts; calendar for reminders and events. Grant only what you want to use.", 13f))
        col.addView(button("Grant permissions") { requestPermissions(permissions, 1) })
        permStatus = text("", 13f)
        col.addView(permStatus)

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

        col.addView(header("Try it"))
        col.addView(button("Open the assistant") {
            startActivity(Intent(this, AssistActivity::class.java))
        })
        col.addView(text("Examples: “set a timer for 10 minutes” · “wake me up at 6:45” · “call mom” · " +
            "“text Alex I'm running late” · “play Daft Punk on Spotify” · “next song” · " +
            "“remind me to call the bank tomorrow at 10” · “add dentist Friday at 3 to my calendar” · " +
            "“what's on my calendar tomorrow”", 13f))
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
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
        roleStatus.text = if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && rm.isRoleHeld(RoleManager.ROLE_ASSISTANT))
                "✓ Jev Assistant is your default assistant"
            else "✗ Not the default assistant yet"
        } else ""
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
