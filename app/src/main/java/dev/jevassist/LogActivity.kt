package dev.jevassist

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Shows the on-device interaction log: newest first; tap an entry for every detail. */
class LogActivity : Activity() {

    private var items: List<JSONObject> = emptyList()
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private val timeFmt = SimpleDateFormat("EEE d MMM, HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Jev logs"

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button("Refresh") { load() })
        buttons.addView(button("Export") { export() })
        buttons.addView(button("Clear") {
            AlertDialog.Builder(this)
                .setMessage("Delete all logs?")
                .setPositiveButton("Delete") { _, _ -> JevLog.clear(this); load() }
                .setNegativeButton("Cancel", null)
                .show()
        })
        col.addView(buttons)
        col.addView(TextView(this).apply {
            text = "Stored only on this phone. Includes what you said, contact names, and the screen " +
                "text the agent saw. Export saves everything to Downloads as a .jsonl file."
            textSize = 12f
            setPadding(0, dp(4), 0, dp(8))
        })
        empty = TextView(this).apply {
            text = if (AppPrefs(this@LogActivity).keepLogs) "No logs yet." else "Logging is off (Settings → Options)."
            setPadding(0, dp(16), 0, 0)
        }
        col.addView(empty)
        list = ListView(this)
        col.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(col)

        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> showDetail(items[pos]) }
        load()
    }

    private fun load() {
        items = JevLog.readAll(this)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@LogActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
                addView(TextView(context).apply { textSize = 16f })
                addView(TextView(context).apply { textSize = 13f; alpha = 0.75f })
            }
            val r = items[position]
            val kind = r.optString("kind")
            val icon = if (kind == "agent") "🤖" else "🎙"
            (row.getChildAt(0) as TextView).text = "$icon  ${r.optString("title")}"
            val intent = r.optString("intent").takeIf { it.isNotEmpty() }?.let { "$it · " } ?: ""
            (row.getChildAt(1) as TextView).text =
                timeFmt.format(Date(r.optLong("time"))) + " · " + intent + r.optString("result", "(no reply)")
            return row
        }
    }

    /** Readable timeline of one interaction, with raw JSON one tap away. */
    private fun showDetail(r: JSONObject) {
        val sb = StringBuilder()
        sb.append(timeFmt.format(Date(r.optLong("time")))).append("\n")
        if (r.has("intent")) {
            sb.append("Intent: ").append(r.optString("intent"))
                .append(" (confidence ").append("%.2f".format(r.optDouble("intent_confidence"))).append(")\n")
        }
        if (r.has("total_ms")) sb.append("Total time: ").append(r.optLong("total_ms")).append(" ms\n")
        sb.append("\n")
        val start = r.optLong("time")
        val events = r.optJSONArray("events")
        if (events != null) {
            for (i in 0 until events.length()) {
                val e = events.optJSONObject(i) ?: continue
                val dt = (e.optLong("t") - start) / 1000.0
                sb.append("+").append("%.1f".format(dt)).append("s  ")
                    .append(e.optString("type").uppercase(Locale.ROOT)).append(": ")
                    .append(e.optString("text")).append("\n")
                val data = e.optJSONObject("data")
                if (data != null && e.optString("type") == "jev") {
                    sb.append(summarizeAnswers(data)).append("\n")
                }
                if (data != null && data.has("screen_text")) {
                    val st = data.optJSONArray("screen_text")
                    val n = st?.length() ?: 0
                    val preview = (0 until minOf(n, 12)).joinToString(" | ") { st!!.optString(it) }
                    sb.append("      screen ($n texts): ").append(preview).append(if (n > 12) " | …" else "").append("\n")
                }
            }
        }
        val text = sb.toString()
        AlertDialog.Builder(this)
            .setTitle(r.optString("title"))
            .setView(scrollingText(text, mono = false))
            .setPositiveButton("Close", null)
            .setNeutralButton("Raw JSON") { _, _ -> showRaw(r) }
            .setNegativeButton("Copy") { _, _ -> copy(text + "\n\n" + r.toString(2)) }
            .show()
    }

    /** One line per question: Jev's pick, confidence, and runners-up. */
    private fun summarizeAnswers(data: JSONObject): String {
        val answers = data.optJSONObject("response")?.optJSONObject("answers") ?: return ""
        val sb = StringBuilder()
        val keys = answers.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val a = answers.optJSONObject(id) ?: continue
            if (a.optString("type") == "noul") {
                sb.append("      ").append(id).append(" = ").append("%.2f".format(a.optDouble("noul"))).append("\n")
                continue
            }
            val probs = a.optJSONObject("probabilities")
            val top = ArrayList<Pair<String, Double>>()
            probs?.keys()?.forEach { k -> top.add(k to probs.optDouble(k)) }
            top.sortByDescending { it.second }
            val runners = top.drop(1).take(2).filter { it.second >= 0.05 }
                .joinToString(", ") { "${it.first} ${"%.2f".format(it.second)}" }
            sb.append("      ").append(id).append(" → ").append(a.optString("choice"))
                .append(" (conf ").append("%.2f".format(a.optDouble("confidence"))).append(")")
            if (runners.isNotEmpty()) sb.append("  next: ").append(runners)
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }

    private fun showRaw(r: JSONObject) {
        val raw = r.toString(2)
        AlertDialog.Builder(this)
            .setTitle("Raw JSON")
            .setView(scrollingText(raw, mono = true))
            .setPositiveButton("Close", null)
            .setNegativeButton("Copy") { _, _ -> copy(raw) }
            .setNeutralButton("Share") { _, _ ->
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, raw.take(400_000)),
                    "Share log entry",
                ))
            }
            .show()
    }

    private fun scrollingText(text: String, mono: Boolean): View = ScrollView(this).apply {
        setPadding(dp(16), dp(8), dp(16), 0)
        addView(TextView(this@LogActivity).apply {
            this.text = text
            textSize = if (mono) 11f else 13f
            if (mono) typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
    }

    private fun copy(text: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Jev log", text.take(900_000)))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun export() {
        val data = JevLog.rawText(this)
        if (data.isEmpty()) {
            Toast.makeText(this, "Nothing to export", Toast.LENGTH_SHORT).show()
            return
        }
        val name = "jev-logs-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".jsonl"
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("couldn't create the file")
                contentResolver.openOutputStream(uri)?.use { it.write(data.toByteArray(Charsets.UTF_8)) }
                Toast.makeText(this, "Saved to Downloads/$name", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, data.takeLast(400_000)),
                "Share logs",
            ))
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(6) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
