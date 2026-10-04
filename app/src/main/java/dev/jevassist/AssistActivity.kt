package dev.jevassist

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class AssistActivity : Activity() {

    private lateinit var prefs: AppPrefs
    private lateinit var interpreter: Interpreter
    private lateinit var actions: Actions
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var afterSpeech = AFTER_NONE

    private var generation = 0
    private var currentAnalysis: Analysis? = null
    private var pendingConfirm: Outcome.Confirm? = null
    private var autoClose: Runnable? = null

    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var reply: TextView
    private lateinit var debug: TextView
    private lateinit var buttons: LinearLayout
    private lateinit var input: EditText
    private lateinit var mic: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        interpreter = Interpreter(this, prefs)
        actions = Actions(this, prefs)
        WakeWordService.instance?.pause() // free the microphone for the recognizer
        buildUi()
        tts = TextToSpeech(this) { st ->
            ttsReady = st == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { main.post { afterSpeechDone() } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { main.post { afterSpeechDone() } }
            })
        }
        begin()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        WakeWordService.instance?.pause()
        begin()
    }

    override fun onStop() {
        super.onStop()
        // Like the old Assistant: once you leave, the overlay goes away.
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        WakeWordService.instance?.resume() // listen for "Hey Jev" again
        super.onDestroy()
    }

    // ------------------------------------------------------------------ flow

    private fun begin() {
        cancelAutoClose()
        generation++
        pendingConfirm = null
        currentAnalysis = null
        clearButtons()
        transcript.text = ""
        reply.text = ""
        debug.text = ""
        if (!prefs.hasApiKey) {
            status.text = "Add your Jev API key first."
            addButton("Open settings") { openSettings() }
            return
        }
        if (WakeWordService.instance != null) {
            // Give the wake-word listener a moment to release the microphone.
            status.text = "…"
            val gen = generation
            main.postDelayed({ if (gen == generation) startListening() }, 300)
        } else {
            startListening()
        }
    }

    private fun handleUtterance(text: String) {
        val said = text.trim()
        if (said.isEmpty()) return
        cancelAutoClose()
        stopListening()
        clearButtons()
        transcript.text = said
        reply.text = ""
        status.text = "Thinking…"
        val gen = ++generation

        val confirm = pendingConfirm
        if (confirm != null) {
            pendingConfirm = null
            worker.execute {
                val answer = try { interpreter.classifyReply(confirm.say, said) } catch (e: Exception) { "unclear" }
                main.post {
                    if (gen != generation) return@post
                    status.text = ""
                    when (answer) {
                        "yes" -> show(confirm.yes())
                        "no" -> finishWith("Okay, I won't send it.")
                        else -> show(confirm)
                    }
                }
            }
            return
        }

        val t0 = SystemClock.elapsedRealtime()
        worker.execute {
            try {
                val a = interpreter.analyze(said)
                val r = interpreter.resolve(a)
                val total = SystemClock.elapsedRealtime() - t0
                main.post {
                    if (gen != generation) return@post
                    currentAnalysis = a
                    showDebug(a, total)
                    handleResolution(r)
                }
            } catch (e: Exception) {
                main.post { if (gen == generation) fail(e.message ?: e.toString()) }
            }
        }
    }

    private fun handleResolution(r: Resolution) {
        status.text = ""
        when (r) {
            is Resolution.Run -> show(actions.run(r.command))
            is Resolution.Clarify -> {
                reply.text = r.prompt
                r.options.forEach { (key, label) -> addButton(label) { choose(r.questionId, key) } }
                addButton("Cancel") { finish() }
                speak(r.prompt)
            }
            is Resolution.Fail -> {
                fail(r.message)
                if (r.offerSearch) {
                    val q = currentAnalysis?.utterance ?: transcript.text.toString()
                    addButton("Search the web") { show(actions.run(Command.WebSearch(q))) }
                }
            }
        }
    }

    /** A "Did you mean…" button was tapped: re-resolve with that answer, no new Jev call needed. */
    private fun choose(questionId: String, key: String) {
        val a = currentAnalysis ?: return
        a.overrides[questionId] = key
        clearButtons()
        status.text = "…"
        val gen = ++generation
        worker.execute {
            val r = try { interpreter.resolve(a) } catch (e: Exception) { Resolution.Fail(e.message ?: "Error") }
            main.post { if (gen == generation) handleResolution(r) }
        }
    }

    private fun show(o: Outcome) {
        clearButtons()
        when (o) {
            is Outcome.Done -> {
                reply.text = o.say
                if (o.leave) {
                    Toast.makeText(applicationContext, o.say, Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    speak(o.say, then = AFTER_CLOSE)
                }
            }
            is Outcome.Confirm -> {
                pendingConfirm = o
                reply.text = o.detail
                addButton("Send") {
                    pendingConfirm = null
                    stopListening()
                    show(o.yes())
                }
                addButton("Cancel") { finish() }
                speak(o.say, then = AFTER_LISTEN)
            }
            is Outcome.Failed -> fail(o.say)
        }
    }

    private fun fail(message: String) {
        status.text = ""
        reply.text = message
        speak(message)
        addButton("Try again") { begin() }
    }

    private fun finishWith(message: String) {
        reply.text = message
        speak(message, then = AFTER_CLOSE)
    }

    private fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    // ---------------------------------------------------------------- speech in

    private fun startListening() {
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            status.text = "Microphone permission is needed. You can also type below."
            addButton("Open settings") { openSettings() }
            return
        }
        createRecognizer(preferOnDevice = true)
    }

    private fun createRecognizer(preferOnDevice: Boolean) {
        recognizer?.destroy()
        val onDevice = preferOnDevice && Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "No speech recognizer on this phone. Type your command below."
            return
        }
        val rec = if (onDevice && Build.VERSION.SDK_INT >= 31) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        recognizer = rec
        rec.setRecognitionListener(Listener(onDevice))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        listening = true
        status.text = "Listening…"
        mic.alpha = 1f
        rec.startListening(intent)
    }

    private fun stopListening() {
        if (listening) recognizer?.cancel()
        listening = false
        mic.alpha = 0.6f
    }

    private inner class Listener(private val onDevice: Boolean) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { status.text = "Listening…" }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            mic.scaleX = 1f + level * 0.35f
            mic.scaleY = 1f + level * 0.35f
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            status.text = "…"
            mic.scaleX = 1f; mic.scaleY = 1f
        }
        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { transcript.text = it }
        }
        override fun onResults(results: Bundle?) {
            listening = false
            mic.alpha = 0.6f
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) {
                status.text = "Didn't catch that. Tap the mic or type."
            } else {
                handleUtterance(text)
            }
        }
        override fun onError(error: Int) {
            listening = false
            mic.alpha = 0.6f
            mic.scaleX = 1f; mic.scaleY = 1f
            // On-device model missing for this language, or busy: fall back to the regular recognizer.
            if (onDevice && error in FALLBACK_ERRORS) {
                createRecognizer(preferOnDevice = false)
                return
            }
            status.text = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    "Didn't catch that. Tap the mic or type."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "Speech recognition needs a connection. Type below instead."
                else -> "Speech recognition error ($error). Tap the mic to retry."
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ---------------------------------------------------------------- speech out

    private fun speak(text: String, then: Int = AFTER_NONE) {
        val t = tts
        if (!prefs.speakResponses || t == null || !ttsReady) {
            afterSpeech = then
            afterSpeechDone(delayClose = 3500)
            return
        }
        afterSpeech = then
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jev-" + SystemClock.elapsedRealtime())
    }

    private fun afterSpeechDone(delayClose: Long = 1200) {
        val next = afterSpeech
        afterSpeech = AFTER_NONE
        when (next) {
            AFTER_LISTEN -> if (pendingConfirm != null) createRecognizer(preferOnDevice = true)
            AFTER_CLOSE -> scheduleClose(delayClose)
        }
    }

    private fun scheduleClose(ms: Long) {
        cancelAutoClose()
        val r = Runnable { finish() }
        autoClose = r
        main.postDelayed(r, ms)
    }

    private fun cancelAutoClose() {
        autoClose?.let { main.removeCallbacks(it) }
        autoClose = null
    }

    // ---------------------------------------------------------------- UI

    private fun showDebug(a: Analysis, totalMs: Long) {
        if (!prefs.showDebug) { debug.visibility = View.GONE; return }
        debug.visibility = View.VISIBLE
        val intent = a.answers.choice("intent")
        val pieces = ArrayList<String>()
        if (intent != null) pieces.add("${intent.choice} ${"%.2f".format(intent.confidence)}")
        a.answers.choice("payload")?.let { p ->
            if (p.choice != "none") pieces.add("“${a.spans[p.choice]}” ${"%.2f".format(p.confidence)}")
        }
        a.answers.choice("contact")?.let { c ->
            val name = a.contacts[c.choice]?.name ?: c.choice
            if (c.choice != "none") pieces.add("$name ${"%.2f".format(c.confidence)}")
        }
        pieces.add("Jev ${a.answers.latencyMs} ms · total $totalMs ms · ${a.answers.inputTokens} tok")
        debug.text = pieces.joinToString("  ·  ")
    }

    private fun buildUi() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val bg = if (night) 0xFF1E1F24.toInt() else Color.WHITE
        val fg = if (night) 0xFFECECF1.toInt() else 0xFF15161A.toInt()
        val muted = if (night) 0xFF9A9CA6.toInt() else 0xFF6B6E78.toInt()
        val chip = if (night) 0xFF2C2E36.toInt() else 0xFFEEF0F5.toInt()

        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val root = FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            setOnClickListener { finish() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            val r = dp(24).toFloat()
            background = GradientDrawable().apply {
                setColor(bg)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(dp(20), dp(18), dp(20), dp(16))
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_DOWN) cancelAutoClose()
                false
            }
        }
        root.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))

        status = TextView(this).apply { setTextColor(muted); textSize = 13f }
        transcript = TextView(this).apply {
            setTextColor(fg); textSize = 22f; setPadding(0, dp(6), 0, dp(6))
        }
        reply = TextView(this).apply { setTextColor(fg); textSize = 17f; setPadding(0, dp(2), 0, dp(4)) }
        debug = TextView(this).apply {
            setTextColor(muted); textSize = 11f; typeface = Typeface.MONOSPACE; setPadding(0, dp(4), 0, dp(4))
        }
        buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(buttons)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        input = EditText(this).apply {
            hint = "Type a command"
            setTextColor(fg)
            setHintTextColor(muted)
            textSize = 16f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            background = GradientDrawable().apply { setColor(chip); cornerRadius = dp(20).toFloat() }
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnFocusChangeListener { _, focused -> if (focused) { cancelAutoClose(); stopListening() } }
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    handleUtterance(v.text.toString()); v.text = null; true
                } else false
            }
        }
        mic = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(chip) }
            alpha = 0.6f
            contentDescription = "Speak"
            setOnClickListener {
                cancelAutoClose()
                if (listening) stopListening() else {
                    clearButtons()
                    if (pendingConfirm == null) { transcript.text = ""; reply.text = "" }
                    startListening()
                }
            }
        }
        row.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(mic, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(10) })

        panel.addView(status)
        panel.addView(transcript)
        panel.addView(reply)
        panel.addView(debug)
        panel.addView(scroller)
        panel.addView(row)

        buttonColors = Pair(chip, fg)
        setContentView(root)
    }

    private var buttonColors = Pair(0, 0)

    private fun addButton(label: String, onClick: () -> Unit) {
        val b = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(buttonColors.second)
            background = GradientDrawable().apply { setColor(buttonColors.first); cornerRadius = dp(18).toFloat() }
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener { cancelAutoClose(); onClick() }
        }
        buttons.addView(b, LinearLayout.LayoutParams(WRAP_CONTENT, dp(40)).apply { rightMargin = dp(8); topMargin = dp(6) })
    }

    private fun clearButtons() = buttons.removeAllViews()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        const val EXTRA_FROM_WAKE = "from_wake"
        const val EXTRA_FROM_WIDGET = "from_widget"
        private const val AFTER_NONE = 0
        private const val AFTER_LISTEN = 1
        private const val AFTER_CLOSE = 2
        // 5 client, 8 busy, 11 server disconnected, 12 language not supported, 13 language unavailable
        private val FALLBACK_ERRORS = setOf(5, 8, 11, 12, 13)
    }
}
