package dev.jevassist

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/** One thing on screen Jev can act on. */
class UiElement(
    val key: String,
    val description: String,
    val node: AccessibilityNodeInfo,
    val editable: Boolean,
)

class ScreenSnapshot(
    val pkg: String,
    val elements: List<UiElement>,
    val texts: List<String>,
    val scrollable: AccessibilityNodeInfo?,
    val inputForSubmit: AccessibilityNodeInfo?,
    val signature: Int,
    /** A spinner / progress bar / skeleton loader is visible. */
    val progressVisible: Boolean,
) {
    private val loadingText = Regex(
        "^(loading|please wait|connecting|just a moment|one moment|signing in|refreshing)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Heuristic: the app hasn't shown real content yet (blank, splash, or spinner with little else). */
    val looksNotReady: Boolean
        get() {
            if (elements.isEmpty()) return true
            if (elements.size + texts.size < 3) return true
            val few = elements.size <= 6
            if (few && progressVisible) return true
            if (few && texts.any { loadingText.containsMatchIn(it) }) return true
            return false
        }
}

/**
 * Turns the current screen into text. Jev is text-only, so instead of a screenshot it gets
 * the accessibility tree: every button, field and label Android exposes, with roles and positions.
 * Password fields are never read.
 */
object ScreenReader {

    fun read(root: AccessibilityNodeInfo, maxElements: Int = 150): ScreenSnapshot {
        val screen = Rect()
        root.getBoundsInScreen(screen)
        val elements = ArrayList<UiElement>()
        val texts = LinkedHashSet<String>()
        var scroll: AccessibilityNodeInfo? = null
        var scrollArea = 0
        var focusedInput: AccessibilityNodeInfo? = null
        var filledInput: AccessibilityNodeInfo? = null
        var progress = false

        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 45 || !n.isVisibleToUser) return
            val own = ownLabel(n)
            if (own.isNotEmpty() && !n.isPassword) texts.add(own.take(120))
            val cls = n.className?.toString() ?: ""
            if (cls.contains("ProgressBar") || cls.contains("ProgressIndicator") || cls.contains("Shimmer")) {
                progress = true
            }

            if (n.isScrollable) {
                val r = Rect(); n.getBoundsInScreen(r)
                val area = r.width() * r.height()
                if (area > scrollArea) { scrollArea = area; scroll = n }
            }
            if (n.isEditable && !n.isPassword) {
                if (n.isFocused) focusedInput = n
                if (!n.text.isNullOrBlank()) filledInput = n
            }

            val actionable = n.isClickable || n.isEditable || n.isCheckable
            if (actionable && !n.isPassword && elements.size < maxElements) {
                val label = if (n.isEditable) fieldName(n) else own.ifEmpty { descendantText(n) }.ifEmpty { idLabel(n) }
                if (label.isNotEmpty() || n.isEditable) {
                    elements.add(UiElement("e${elements.size}", describe(n, label, screen), n, n.isEditable))
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)

        val textList = texts.toList()
        return ScreenSnapshot(
            pkg = root.packageName?.toString() ?: "",
            elements = elements,
            texts = textList,
            scrollable = scroll,
            inputForSubmit = focusedInput ?: filledInput,
            signature = (textList.joinToString("|") + elements.size).hashCode(),
            progressVisible = progress,
        )
    }

    private fun clean(s: CharSequence?): String =
        s?.toString()?.replace(Regex("\\s+"), " ")?.trim() ?: ""

    private fun ownLabel(n: AccessibilityNodeInfo): String {
        val t = clean(n.text)
        if (t.isNotEmpty()) return t
        val d = clean(n.contentDescription)
        if (d.isNotEmpty()) return d
        return clean(n.hintText)
    }

    private fun fieldName(n: AccessibilityNodeInfo): String {
        val hint = clean(n.hintText)
        val desc = clean(n.contentDescription)
        return hint.ifEmpty { desc }.ifEmpty { idLabel(n) }
    }

    private fun descendantText(n: AccessibilityNodeInfo): String {
        val found = ArrayList<String>()
        fun walk(c: AccessibilityNodeInfo, depth: Int) {
            if (found.size >= 3 || depth > 6) return
            for (i in 0 until c.childCount) {
                val ch = c.getChild(i) ?: continue
                if (!ch.isVisibleToUser || ch.isPassword) continue
                val l = ownLabel(ch)
                if (l.isNotEmpty()) found.add(l)
                if (found.size >= 3) return
                walk(ch, depth + 1)
            }
        }
        walk(n, 0)
        return found.joinToString(" · ").take(90)
    }

    private fun idLabel(n: AccessibilityNodeInfo): String =
        n.viewIdResourceName?.substringAfter(":id/", "")?.replace('_', ' ')?.trim() ?: ""

    private fun describe(n: AccessibilityNodeInfo, label: String, screen: Rect): String {
        val cls = n.className?.toString() ?: ""
        val role = when {
            n.isEditable -> "Text field"
            cls.endsWith("Switch") || cls.endsWith("SwitchCompat") || cls.endsWith("SwitchMaterial") -> "Switch"
            cls.endsWith("CheckBox") -> "Checkbox"
            cls.endsWith("RadioButton") -> "Option"
            cls.endsWith("ImageButton") || cls.endsWith("ImageView") -> "Icon button"
            cls.endsWith("Button") -> "Button"
            cls.endsWith("Tab") || cls.contains("TabView") -> "Tab"
            else -> "Item"
        }
        val sb = StringBuilder(role)
        if (label.isNotEmpty()) sb.append(" “").append(label.take(90)).append("”")
        if (n.isEditable) {
            val value = clean(n.text)
            sb.append(if (value.isEmpty() || value == label) " (empty)" else " containing “${value.take(60)}”")
        }
        if (n.isCheckable) sb.append(if (n.isChecked) " (on)" else " (off)")
        if (!n.isEnabled) sb.append(" (disabled)")
        val r = Rect()
        n.getBoundsInScreen(r)
        if (screen.height() > 0 && screen.width() > 0) {
            val v = when {
                r.centerY() < screen.top + screen.height() / 3 -> "top"
                r.centerY() < screen.top + 2 * screen.height() / 3 -> "middle"
                else -> "bottom"
            }
            val h = when {
                r.centerX() < screen.left + screen.width() / 3 -> " left"
                r.centerX() < screen.left + 2 * screen.width() / 3 -> ""
                else -> " right"
            }
            sb.append(", ").append(v).append(h)
        }
        return sb.toString()
    }
}

/** What the agent needs from the on-screen overlay (all calls may block the agent thread). */
interface AgentUi {
    fun status(text: String)
    fun confirm(question: String): Boolean
    fun askChoice(question: String, options: List<Pair<String, String>>): String?
    fun finish(text: String)
}

/**
 * The loop: read the screen -> one Jev call -> do one action -> repeat.
 * Every step is a closed choice among what's actually on screen, which is exactly what
 * Jev is good at. Multi-step planning is not, so goals should be short and concrete.
 */
class ScreenAgent(
    private val svc: AccessibilityService,
    private val prefs: AppPrefs,
    private val ui: AgentUi,
    private val lastEventAt: () -> Long,
) {
    @Volatile var cancelled = false

    fun run(goal: String, pkg: String?) {
        try {
            loop(goal, pkg, pkg?.let { appLabelOf(it) })
        } catch (e: Exception) {
            ui.finish("Stopped: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun loop(goal: String, pkg: String?, appLabel: String?) {
        val key = prefs.apiKey ?: return ui.finish("Add your Jev API key first.")
        val client = JevClient(key, prefs.model)
        val history = ArrayList<String>()
        val spans = LinkedHashMap<String, String>()
        TextTools.spanCandidates(TextTools.words(goal)).forEachIndexed { i, s -> spans["s$i"] = s }

        if (pkg != null) {
            ui.status("Opening ${appLabel ?: "the app"}…")
            val launch = svc.packageManager.getLaunchIntentForPackage(pkg)
                ?: return ui.finish("I can't open ${appLabel ?: pkg}.")
            svc.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            history.add("Opened ${appLabel ?: pkg}")
            val until = SystemClock.elapsedRealtime() + 6000
            while (SystemClock.elapsedRealtime() < until && !cancelled) {
                if (svc.rootInActiveWindow?.packageName?.toString() == pkg) break
                Thread.sleep(150)
            }
        }

        var lastSig = 0
        var sameCount = 0
        var step = 0
        var jevWaits = 0
        var stuckRecheckSig = 0
        var stuckRechecks = 0
        while (step < MAX_STEPS) {
            if (cancelled) return
            waitForSettle()
            val snap = waitUntilReady() ?: return
            if (prefs.isAgentBlocked(snap.pkg, appLabelOf(snap.pkg))) {
                return ui.finish("Stopped: you've set this app as off-limits.")
            }
            sameCount = if (snap.signature == lastSig) sameCount + 1 else 0
            lastSig = snap.signature
            if (sameCount >= 3) return ui.finish("I'm stuck: the screen isn't changing.")

            step++
            ui.status("Step $step: reading the screen…")
            val (questions, labels) = buildQuestions(snap, spans)
            val state = JSONObject()
                .put("goal", goal)
                .put("app", appLabelOf(snap.pkg))
                .put("steps_done", JSONArray(history))
                .put("screen_text", JSONArray(snap.texts.take(70)))
            val answers = client.ask(state, questions)
            if (cancelled) return
            val action = answers.choice("action") ?: return ui.finish("Jev didn't answer.")

            var choice = action.choice
            val topP = action.probabilities[choice] ?: 0.0
            if (topP < ASK_BELOW) {
                val opts = action.top(3).filter { it.second > 0.05 }.map { it.first to (labels[it.first] ?: it.first) }
                if (opts.size >= 2) {
                    choice = ui.askChoice("Not sure. What should I do next?", opts) ?: return ui.finish("Stopped.")
                }
            }

            when (choice) {
                "done" -> return ui.finish("Done.")
                "wait" -> {
                    // Jev says the screen is still loading. Doesn't count as a step.
                    step--
                    sameCount = 0
                    if (++jevWaits > MAX_JEV_WAITS) return ui.finish("The app didn't finish loading.")
                    ui.status("Waiting for the app to load…")
                    Thread.sleep(2000)
                }
                "stuck" -> {
                    // Give the screen one more chance before giving up: it may still be loading.
                    if (stuckRecheckSig != snap.signature && stuckRechecks < 2) {
                        stuckRecheckSig = snap.signature
                        stuckRechecks++
                        step--
                        sameCount = 0
                        ui.status("Taking another look…")
                        Thread.sleep(3000)
                    } else {
                        return ui.finish("I couldn't find a way to do that from here.")
                    }
                }
                "back" -> {
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    history.add("Went back")
                }
                "scroll_down", "scroll_up" -> {
                    val dir = if (choice == "scroll_down") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    snap.scrollable?.performAction(dir)
                    history.add(if (choice == "scroll_down") "Scrolled down" else "Scrolled up")
                }
                "submit" -> {
                    if (Build.VERSION.SDK_INT >= 30) {
                        snap.inputForSubmit?.performAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                        )
                    }
                    history.add("Pressed Enter")
                }
                else -> {
                    val el = snap.elements.firstOrNull { it.key == choice } ?: continue
                    if (el.editable) {
                        val text = spans[answers.choice("type_text")?.choice]
                            ?: return ui.finish("I don't know what to type into ${el.description}.")
                        if (prefs.agentConfirmEveryStep && !ui.confirm("Type “$text” into ${el.description}?")) {
                            return ui.finish("Okay, stopped.")
                        }
                        el.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                        }
                        el.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        history.add("Typed “$text” into ${el.description}")
                    } else {
                        val riskP = answers.choice("risky")?.probabilities?.get(choice) ?: 0.0
                        if (prefs.agentConfirmEveryStep || riskP >= RISK_AT || RISK_WORDS.containsMatchIn(el.description)) {
                            ui.status("Waiting for you…")
                            if (!ui.confirm("Tap ${el.description}?")) return ui.finish("Okay, stopped before that.")
                        }
                        tap(el.node)
                        history.add("Tapped ${el.description}")
                    }
                }
            }
        }
        ui.finish("I stopped after $MAX_STEPS steps without finishing.")
    }

    private fun buildQuestions(
        snap: ScreenSnapshot,
        spans: Map<String, String>,
    ): Pair<JSONObject, Map<String, String>> {
        val labels = LinkedHashMap<String, String>()
        snap.elements.forEach { labels[it.key] = (if (it.editable) "Type into " else "Tap ") + it.description }
        if (snap.scrollable != null) {
            labels["scroll_down"] = "Scroll down to see more of the screen"
            labels["scroll_up"] = "Scroll up"
        }
        if (Build.VERSION.SDK_INT >= 30 && snap.inputForSubmit != null) {
            labels["submit"] = "Press Enter / Search on the keyboard to submit the text already typed"
        }
        labels["wait"] = "Wait: the screen is still loading, blank, or showing a splash or loading screen"
        labels["back"] = "Go back to the previous screen"
        labels["done"] = "Nothing to do: the goal is already achieved on this screen"
        labels["stuck"] = "The goal can't be achieved from this screen"

        val q = JSONObject()
        q.put("action", choice(
            "The assistant is operating the phone to achieve `goal`. `steps_done` lists what it already did, " +
                "and `screen_text` is what is on the screen now. What is the single next action that moves closest to `goal`?",
            labels,
        ))
        val risky = LinkedHashMap<String, String?>()
        snap.elements.filter { !it.editable }.forEach { risky[it.key] = it.description }
        risky["none"] = "None of these"
        q.put("risky", choice(
            "Which element, if tapped, would send a message, make a payment or purchase, place an order or booking, " +
                "post publicly, or delete something?",
            risky,
        ))
        if (snap.elements.any { it.editable }) {
            val t = LinkedHashMap<String, String?>()
            spans.forEach { (k, v) -> t[k] = v }
            t["none"] = "The goal contains no text to type."
            q.put("type_text", choice(
                "If the assistant types into a text field to achieve `goal`, which option is exactly the text to type " +
                    "(for example the search words or the message), without command words or the app name?",
                t,
            ))
        }
        return q to labels
    }

    private fun choice(instructions: String, criteria: Map<String, String?>): JSONObject {
        val c = JSONObject()
        for ((k, v) in criteria) c.put(k, v ?: JSONObject.NULL)
        return JSONObject().put("type", "choice").put("instructions", instructions).put("criteria", c)
    }

    private fun tap(node: AccessibilityNodeInfo) {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        // Some apps ignore accessibility clicks: fall back to a real tap at the element's center.
        val r = Rect()
        node.getBoundsInScreen(r)
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        svc.dispatchGesture(gesture, null, null)
    }

    /**
     * Reads the screen, waiting (up to 12 s) while it looks not ready: no window yet, our own
     * overlay, blank, a spinner with little else, or still changing. Returns null if cancelled.
     */
    private fun waitUntilReady(): ScreenSnapshot? {
        val started = SystemClock.elapsedRealtime()
        val until = started + READY_TIMEOUT_MS
        var prevSig: Int? = null
        var shownWaiting = false
        var last: ScreenSnapshot? = null
        while (!cancelled) {
            val root = svc.rootInActiveWindow
            val rootPkg = root?.packageName?.toString()
            if (root != null && rootPkg != null && rootPkg != svc.packageName) {
                val snap = ScreenReader.read(root)
                last = snap
                // Real content + (unchanged, or we've given it a moment: some screens animate forever).
                val stable = prevSig == snap.signature
                val gaveItAMoment = SystemClock.elapsedRealtime() - started > 1200
                if (!snap.looksNotReady && (stable || gaveItAMoment)) return snap
                prevSig = snap.signature
            }
            if (SystemClock.elapsedRealtime() >= until) {
                // Waited long enough: let Jev judge whatever is there (it can still choose "wait").
                return last ?: return null.also { ui.finish("The app didn't open.") }
            }
            if (!shownWaiting && (last == null || last.looksNotReady)) {
                ui.status("Waiting for the app to load…")
                shownWaiting = true
            }
            Thread.sleep(400)
        }
        return null
    }

    /** Wait until the app stops changing the screen (or 2.5 s). */
    private fun waitForSettle() {
        Thread.sleep(350)
        val until = SystemClock.elapsedRealtime() + 2500
        while (SystemClock.elapsedRealtime() < until && !cancelled) {
            if (SystemClock.elapsedRealtime() - lastEventAt() > 300) return
            Thread.sleep(100)
        }
    }

    private fun appLabelOf(pkg: String): String = try {
        val pm = svc.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    companion object {
        const val MAX_STEPS = 15
        private const val MAX_JEV_WAITS = 5
        private const val READY_TIMEOUT_MS = 12_000L
        private const val ASK_BELOW = 0.35
        private const val RISK_AT = 0.25
        private val RISK_WORDS = Regex(
            "\\b(send|pay|buy|purchase|order|checkout|check out|book|delete|remove|post|publish|transfer|subscribe|confirm|place)\\b",
            RegexOption.IGNORE_CASE,
        )
    }
}
