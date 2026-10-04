package dev.jevassist

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class JevException(message: String, val status: Int = 0) : Exception(message)

/** A Choice answer: the picked option, the full distribution, and Jev's confidence. */
class ChoiceAnswer(
    val choice: String,
    val probabilities: Map<String, Double>,
    val confidence: Double,
) {
    fun top(n: Int): List<Pair<String, Double>> =
        probabilities.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }
}

class JevAnswers(
    private val answers: JSONObject,
    val model: String,
    val inputTokens: Int,
    val latencyMs: Long,
    /** Exact JSON sent and received (no API key: that's only in the HTTP header). For the log. */
    val rawRequest: String = "",
    val rawResponse: String = "",
) {
    fun choice(id: String): ChoiceAnswer? {
        val a = answers.optJSONObject(id) ?: return null
        if (a.optString("type") != "choice") return null
        val probs = LinkedHashMap<String, Double>()
        a.optJSONObject("probabilities")?.let { p ->
            val keys = p.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                probs[k] = p.optDouble(k, 0.0)
            }
        }
        return ChoiceAnswer(a.optString("choice"), probs, a.optDouble("confidence", 0.0))
    }

    fun noul(id: String): Double? {
        val a = answers.optJSONObject(id) ?: return null
        if (a.optString("type") != "noul") return null
        return a.optDouble("noul")
    }
}

/**
 * POST https://api.typesafe.ai/v1/systemone  (docs: https://docs.typesafe.ai/api)
 * Blocking: always call from a background thread.
 */
class JevClient(private val apiKey: String, private val model: String) {

    fun ask(state: Any, questions: JSONObject): JevAnswers {
        val bodyText = JSONObject()
            .put("state", state)
            .put("model", model)
            .put("questions", questions)
            .toString()
        val body = bodyText.toByteArray(Charsets.UTF_8)

        var attempt = 0
        while (true) {
            val started = System.nanoTime()
            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            try {
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                val latency = (System.nanoTime() - started) / 1_000_000

                if (code in 200..299) {
                    val json = JSONObject(text)
                    return JevAnswers(
                        answers = json.optJSONObject("answers") ?: JSONObject(),
                        model = json.optString("model"),
                        inputTokens = json.optJSONObject("usage")?.optInt("input_tokens") ?: 0,
                        latencyMs = latency,
                        rawRequest = bodyText,
                        rawResponse = text,
                    )
                }
                // 429 = rate limited, 529 = overloaded: retry with backoff (as TypeSafe recommends).
                if ((code == 429 || code == 529 || code >= 500) && attempt < 2) {
                    Thread.sleep(300L shl attempt)
                    attempt++
                    continue
                }
                throw JevException(describe(code, text), code)
            } catch (e: SocketTimeoutException) {
                throw JevException("Jev took too long to answer. Check your connection.")
            } catch (e: IOException) {
                if (attempt < 1) {
                    attempt++
                    continue
                }
                throw JevException("Couldn't reach Jev: ${e.message ?: "network error"}")
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun describe(code: Int, body: String): String = when (code) {
        401 -> "Jev rejected the API key (401). Re-enter it in Jev Assistant settings."
        422 -> "Jev rejected the request (422): ${body.take(300)}"
        429 -> "Jev rate limit reached (429). Try again in a moment."
        529 -> "Jev is overloaded right now (529). Try again in a moment."
        else -> "Jev error $code: ${body.take(300)}"
    }

    companion object {
        const val ENDPOINT = "https://api.typesafe.ai/v1/systemone"
    }
}
