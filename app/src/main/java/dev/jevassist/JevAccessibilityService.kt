package dev.jevassist

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * One-shot "arm" set right after you confirm a WhatsApp message: for 10 s, inside WhatsApp only,
 * and only if the text box holds exactly the confirmed message, the service taps Send.
 */
object AutoSend {
    @Volatile var expectedText: String? = null
    @Volatile var armedUntil: Long = 0L

    fun arm(text: String) {
        expectedText = text
        armedUntil = SystemClock.elapsedRealtime() + 10_000
    }

    fun disarm() {
        expectedText = null
        armedUntil = 0L
    }

    val isArmed: Boolean get() = expectedText != null && SystemClock.elapsedRealtime() < armedUntil
}

/**
 * Jev Assistant's accessibility service ("screen control"). It acts only when you ask:
 *  1. runs the screen agent (operate an app for you), with a small overlay: progress, questions, Stop;
 *  2. taps WhatsApp's Send for a message you just confirmed.
 * Otherwise it only notes *when* the screen last changed, to know when an app has settled.
 */
class JevAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastEventAt = 0L
    @Volatile private var agent: ScreenAgent? = null
    private var overlay: LinearLayout? = null
    private var statusView: TextView? = null
    private var buttonRow: LinearLayout? = null
    private var palette = Pair(0xFFEEF0F5.toInt(), 0xFF15161A.toInt())

    @Volatile private var pendingLatch: CountDownLatch? = null
    @Volatile private var pendingAnswer: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        stopAgent()
        removeOverlay()
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventAt = SystemClock.elapsedRealtime()
        if (AutoSend.isArmed) tryAutoSend(event)
    }

    // ------------------------------------------------------------ WhatsApp

    private fun tryAutoSend(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in WHATSAPP_PACKAGES) return
        val root = rootInActiveWindow ?: return
        val expected = AutoSend.expectedText ?: return
        val entry = root.findAccessibilityNodeInfosByViewId("$pkg:id/entry").firstOrNull() ?: return
        val typed = entry.text?.toString()?.trim() ?: return
        if (typed != expected.trim()) return // never send anything but the confirmed message
        val send = root.findAccessibilityNodeInfosByViewId("$pkg:id/send")
            .firstOrNull { it.isEnabled && it.isVisibleToUser } ?: return
        if (send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) AutoSend.disarm()
    }

    // ------------------------------------------------------------ agent

    fun startAgent(goal: String, pkg: String?) {
        stopAgent()
        val a = ScreenAgent(this, AppPrefs(this), OverlayUi()) { lastEventAt }
        agent = a
        main.post { showOverlay() }
        Thread({
            Thread.sleep(500) // let the assistant overlay close so we act on the app underneath
            a.run(goal, pkg)
        }, "jev-agent").start()
    }

    private fun stopAgent() {
        agent?.cancelled = true
        agent = null
        pendingLatch?.countDown()
    }

    /** The overlay, driven from the agent thread. */
    private inner class OverlayUi : AgentUi {
        override fun status(text: String) {
            main.post { statusView?.text = text; setButtons(emptyList()) }
        }

        override fun confirm(question: String): Boolean =
            ask(question, listOf("yes" to "Do it", "no" to "Stop")) == "yes"

        override fun askChoice(question: String, options: List<Pair<String, String>>): String? =
            ask(question, options)

        override fun finish(text: String) {
            agent = null
            main.post {
                if (overlay == null) showOverlay()
                statusView?.text = text
                setButtons(emptyList())
                main.postDelayed({ if (agent == null) removeOverlay() }, 8000)
            }
        }

        override fun waitForUser(message: String, isDone: () -> Boolean): Boolean {
            val latch = CountDownLatch(1)
            pendingAnswer = null
            pendingLatch = latch
            main.post {
                if (overlay == null) showOverlay()
                statusView?.text = message
                setButtons(listOf("continue" to "Continue", "stop" to "Stop"))
            }
            val until = SystemClock.elapsedRealtime() + 3 * 60_000
            var doneStreak = 0
            try {
                while (SystemClock.elapsedRealtime() < until) {
                    if (latch.await(500, TimeUnit.MILLISECONDS)) {
                        return pendingAnswer == "continue" && agent != null
                    }
                    if (agent == null) return false // Stop pressed
                    doneStreak = if (isDone()) doneStreak + 1 else 0
                    if (doneStreak >= 2) return true // prompt gone: carry on by ourselves
                }
                return false
            } finally {
                pendingLatch = null
                main.post { setButtons(emptyList()) }
            }
        }

        private fun ask(question: String, options: List<Pair<String, String>>): String? {
            val latch = CountDownLatch(1)
            pendingAnswer = null
            pendingLatch = latch
            main.post {
                statusView?.text = question
                setButtons(options)
            }
            latch.await(90, TimeUnit.SECONDS)
            pendingLatch = null
            return pendingAnswer
        }
    }

    private fun answer(key: String) {
        pendingAnswer = key
        setButtons(emptyList())
        pendingLatch?.countDown()
    }

    // ------------------------------------------------------------ overlay window

    private fun showOverlay() {
        if (overlay != null) return
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val bg = if (night) 0xF21E1F24.toInt() else 0xF2FFFFFF.toInt()
        val fg = if (night) 0xFFECECF1.toInt() else 0xFF15161A.toInt()
        val chip = if (night) 0xFF2C2E36.toInt() else 0xFFEEF0F5.toInt()
        palette = Pair(chip, fg)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(18).toFloat() }
            elevation = dp(6).toFloat()
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val status = TextView(this).apply { setTextColor(fg); textSize = 14f; text = "Jev is working…" }
        statusView = status
        val stop = Button(this).apply {
            text = "Stop"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(0xFFD93F3F.toInt()); cornerRadius = dp(14).toFloat() }
            setOnClickListener {
                stopAgent()
                statusView?.text = "Stopped."
                setButtons(emptyList())
                main.postDelayed({ removeOverlay() }, 800)
            }
        }
        top.addView(status, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(stop, LinearLayout.LayoutParams(WRAP_CONTENT, dp(36)).apply { leftMargin = dp(8) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttonRow = row
        val scroller = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        box.addView(top)
        box.addView(scroller)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = dp(36)
            horizontalMargin = 0.03f
        }
        try {
            getSystemService(WindowManager::class.java).addView(box, lp)
            overlay = box
        } catch (e: Exception) {
            overlay = null
        }
    }

    private fun setButtons(options: List<Pair<String, String>>) {
        val row = buttonRow ?: return
        row.removeAllViews()
        options.forEach { (key, label) ->
            row.addView(Button(this).apply {
                text = label.take(60)
                isAllCaps = false
                setTextColor(palette.second)
                background = GradientDrawable().apply { setColor(palette.first); cornerRadius = dp(14).toFloat() }
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { answer(key) }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(38)).apply { rightMargin = dp(6); topMargin = dp(6) })
        }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        try { getSystemService(WindowManager::class.java).removeView(v) } catch (e: Exception) { }
        overlay = null
        statusView = null
        buttonRow = null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        @Volatile var instance: JevAccessibilityService? = null
        val running: Boolean get() = instance != null
        val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
    }
}
