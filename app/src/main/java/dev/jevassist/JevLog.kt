package dev.jevassist

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * On-device log of everything the assistant does: what you said, every Jev request and
 * response in full, what the app decided, and what it replied. One JSON line per interaction
 * in the app's private storage (never uploaded), capped at ~8 MB across two files.
 */
object JevLog {
    private const val MAX_BYTES = 4_000_000L

    private fun dir(ctx: Context) = File(ctx.filesDir, "logs").apply { mkdirs() }
    private fun current(ctx: Context) = File(dir(ctx), "log.jsonl")
    private fun previous(ctx: Context) = File(dir(ctx), "log.1.jsonl")

    @Synchronized
    fun write(ctx: Context, record: JSONObject) {
        if (!AppPrefs(ctx).keepLogs) return
        try {
            val f = current(ctx)
            if (f.length() > MAX_BYTES) {
                previous(ctx).delete()
                f.renameTo(previous(ctx))
            }
            FileOutputStream(current(ctx), true).use {
                it.write((record.toString() + "\n").toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            // Logging must never break the assistant.
        }
    }

    /** Newest first. */
    @Synchronized
    fun readAll(ctx: Context): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        for (f in listOf(previous(ctx), current(ctx))) {
            if (!f.exists()) continue
            f.forEachLine { line ->
                if (line.isNotBlank()) try { out.add(JSONObject(line)) } catch (e: Exception) { }
            }
        }
        out.reverse()
        return out
    }

    /** Raw JSON lines, oldest first (for export). */
    @Synchronized
    fun rawText(ctx: Context): String = listOf(previous(ctx), current(ctx))
        .filter { it.exists() }
        .joinToString("") { it.readText() }

    @Synchronized
    fun clear(ctx: Context) {
        previous(ctx).delete()
        current(ctx).delete()
    }
}

/** Builds one log record as the interaction unfolds; written once at the end. */
class LogRecord(kind: String, title: String) {
    val json: JSONObject = JSONObject()
        .put("time", System.currentTimeMillis())
        .put("kind", kind)
        .put("title", title)
    private val events = JSONArray()
    @Volatile private var written = false

    init {
        json.put("events", events)
    }

    @Synchronized
    fun put(key: String, value: Any?): LogRecord {
        json.put(key, value ?: JSONObject.NULL)
        return this
    }

    @Synchronized
    fun event(type: String, text: String, data: JSONObject? = null) {
        val e = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("type", type)
            .put("text", text)
        if (data != null) e.put("data", data)
        events.put(e)
    }

    /** Full Jev request + response for one call. */
    fun jev(label: String, answers: JevAnswers) {
        val data = JSONObject()
            .put("latency_ms", answers.latencyMs)
            .put("input_tokens", answers.inputTokens)
            .put("model", answers.model)
        try { data.put("request", JSONObject(answers.rawRequest)) } catch (e: Exception) { data.put("request", answers.rawRequest) }
        try { data.put("response", JSONObject(answers.rawResponse)) } catch (e: Exception) { data.put("response", answers.rawResponse) }
        event("jev", "$label: ${answers.latencyMs} ms, ${answers.inputTokens} tokens", data)
    }

    fun writeOnce(ctx: Context) {
        if (written) return
        written = true
        JevLog.write(ctx, json)
    }
}
