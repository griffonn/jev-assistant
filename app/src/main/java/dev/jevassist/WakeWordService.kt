package dev.jevassist

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.math.max

/**
 * Listens for the wake phrase with Vosk, a small offline recognizer, restricted to a
 * grammar of just the phrase plus "[unk]" (anything else). Audio never leaves the phone.
 *
 * When the phrase is heard: short beep, stop listening, open the assistant overlay.
 * The overlay pauses this service while it uses the mic and resumes it when it closes.
 */
class WakeWordService : Service() {

    private lateinit var prefs: AppPrefs
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var model: Model? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.wakeEnabled = false
            shutdown()
            return START_NOT_STICKY
        }
        try {
            goForeground()
        } catch (e: Exception) {
            lastError = "Couldn't start background listening: ${e.message}"
            shutdown()
            return START_NOT_STICKY
        }
        paused = false
        ensureListening()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        instance = null
        main.removeCallbacksAndMessages(null)
        model?.close()
        model = null
        super.onDestroy()
    }

    // ------------------------------------------------ called by the assistant overlay

    /** The overlay needs the microphone. */
    fun pause() {
        paused = true
        running = false
    }

    /** The overlay closed: listen again. */
    fun resume() {
        if (!prefs.wakeEnabled) return
        paused = false
        main.postDelayed({ ensureListening() }, 400)
    }

    private fun shutdown() {
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------ listening loop

    private fun ensureListening() {
        if (paused || !prefs.wakeEnabled) return
        if (worker != null) {
            // The previous loop is still releasing the mic; check again shortly.
            if (!running) main.postDelayed({ ensureListening() }, 200)
            return
        }
        running = true
        worker = Thread({ listenLoop() }, "jev-wake").also { it.start() }
    }

    private fun listenLoop() {
        var audio: AudioRecord? = null
        var rec: Recognizer? = null
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                lastError = "Microphone permission is missing."
                return
            }
            val m = model ?: Model(WakeModel.dir(this).absolutePath).also { model = it }
            val phrase = prefs.wakePhrase
            val phraseTokens = phrase.split(" ").filter { it.isNotBlank() }
            rec = Recognizer(m, SAMPLE_RATE.toFloat(), JSONArray().put(phrase).put("[unk]").toString())

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            audio = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 6400) * 2,
            )
            if (audio.state != AudioRecord.STATE_INITIALIZED) {
                lastError = "Couldn't open the microphone."
                return
            }
            audio.startRecording()
            lastError = null
            val buf = ShortArray(1600) // 0.1 s
            while (running) {
                val n = audio.read(buf, 0, buf.size)
                if (n <= 0) continue
                val isFinal = rec.acceptWaveForm(buf, n)
                val heard = if (isFinal) JSONObject(rec.getResult()).optString("text")
                else JSONObject(rec.getPartialResult()).optString("partial")
                if (containsPhrase(heard, phraseTokens)) {
                    running = false
                    main.post { onWake() }
                }
            }
        } catch (e: Throwable) {
            lastError = "Wake word error: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            try { audio?.stop() } catch (e: Exception) { }
            audio?.release()
            rec?.close()
            worker = null
        }
    }

    private fun containsPhrase(heard: String, phrase: List<String>): Boolean {
        if (heard.isBlank() || phrase.isEmpty()) return false
        val words = heard.split(" ").filter { it.isNotBlank() && it != "[unk]" }
        if (words.size < phrase.size) return false
        for (i in 0..words.size - phrase.size) {
            if (words.subList(i, i + phrase.size) == phrase) return true
        }
        return false
    }

    private fun onWake() {
        val power = getSystemService(PowerManager::class.java)
        val keyguard = getSystemService(KeyguardManager::class.java)
        // Don't open over the lock screen: anyone could otherwise call or message as you.
        if (!power.isInteractive || keyguard.isKeyguardLocked) {
            resume()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            lastError = "Allow “Display over other apps” so Hey Jev can open the assistant."
            notifyProblem(lastError!!)
            resume()
            return
        }
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 70).apply {
                startTone(ToneGenerator.TONE_PROP_ACK, 120)
                main.postDelayed({ release() }, 400)
            }
        } catch (e: Exception) {
            // no beep, no problem
        }
        startActivity(
            Intent(this, AssistActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(AssistActivity.EXTRA_FROM_WAKE, true)
        )
    }

    // ------------------------------------------------ notification

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Hey Jev listening", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setContentTitle("Listening for “${prefs.wakePhrase}”")
            .setContentText("Offline. Tap for settings.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Turn off", stop,
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notifyProblem(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            PROBLEM_ID,
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Hey Jev needs a setting")
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        const val ACTION_STOP = "dev.jevassist.WAKE_STOP"
        const val CHANNEL = "wake"
        private const val NOTIF_ID = 1
        private const val PROBLEM_ID = 2
        private const val SAMPLE_RATE = 16000

        @Volatile var instance: WakeWordService? = null
        @Volatile var lastError: String? = null

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, WakeWordService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, WakeWordService::class.java).setAction(ACTION_STOP))
        }
    }
}

/** Downloads and unpacks the small English Vosk model (~40 MB) on first use. */
object WakeModel {
    private const val URL_STR = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

    fun dir(ctx: Context) = File(ctx.filesDir, "vosk-model")

    fun isReady(ctx: Context) = File(dir(ctx), "am/final.mdl").exists()

    /** Blocking; call from a background thread. */
    fun download(ctx: Context, progress: (String) -> Unit) {
        val target = dir(ctx)
        val tmp = File(ctx.filesDir, "vosk-model.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val conn = (URL(URL_STR).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            val total = conn.contentLengthLong
            var read = 0L
            var lastPct = -1
            val counting = object : java.io.FilterInputStream(conn.inputStream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) {
                        read += n
                        if (total > 0) {
                            val pct = (read * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; progress("Downloading… $pct%") }
                        }
                    }
                    return n
                }
            }
            ZipInputStream(counting.buffered()).use { zip ->
                val root = tmp.canonicalPath + File.separator
                while (true) {
                    val e = zip.nextEntry ?: break
                    // Drop the zip's top folder ("vosk-model-small-en-us-0.15/").
                    val rel = e.name.substringAfter('/', "")
                    if (rel.isEmpty()) continue
                    val out = File(tmp, rel)
                    if (!out.canonicalPath.startsWith(root)) continue // zip-slip guard
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (!File(tmp, "am/final.mdl").exists()) throw IllegalStateException("Model download looks incomplete.")
        target.deleteRecursively()
        if (!tmp.renameTo(target)) throw IllegalStateException("Couldn't install the model.")
        progress("Model ready.")
    }
}
