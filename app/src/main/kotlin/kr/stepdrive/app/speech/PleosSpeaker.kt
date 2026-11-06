package kr.stepdrive.app.speech

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kr.stepdrive.app.diag.SignalCheck
import java.util.ArrayDeque
import java.util.Locale

/**
 * Pleos TextToSpeech SDK in ON_DEVICE mode (no app registration or network needed).
 * Keeps a small queue: urgent lines (hazard warnings) flush whatever is waiting.
 */
/** [onUnavailable] receives the still-queued lines when the Pleos speech service cannot be initialized. */
class PleosSpeaker(context: Context, private val onUnavailable: (List<String>) -> Unit = {}) : Speaker {
    private val tts = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
    /** text with the time it was queued; lines older than [MAX_AGE_MS] are dropped instead of spoken late. */
    private val queue = ArrayDeque<Pair<String, Long>>()
    private val handler = Handler(Looper.getMainLooper())
    private var utteranceSeq = 0
    @Volatile private var released = false
    @Volatile private var ready = false
    @Volatile private var speaking = false
    @Volatile var failed = false
        private set

    private val events = object : EventListener {
        override fun onStart() { Log.i(TAG, "tts onStart"); speaking = true; SignalCheck.ok("음성", "Pleos TTS 재생 시작") }
        override fun onReady() { Log.i(TAG, "tts onReady") }
        override fun onDone() { Log.i(TAG, "tts onDone"); finished() }
        override fun onStop() { Log.i(TAG, "tts onStop"); finished() }
        override fun onError(error: ErrorType) { Log.w(TAG, "tts error $error"); finished() }
        override fun onUpdatedRms(rms: Double) = Unit
    }

    init {
        runCatching {
            // initialize() creates the SDK's internal engine; listeners can only be attached after that call.
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    if (released) return
                    Log.i(TAG, "Pleos TTS initialized")
                    runCatching { tts.setLocale(Locale.KOREA) }.onFailure { Log.w(TAG, "setLocale: $it") }
                    ready = true; SignalCheck.ok("음성", "Pleos TTS 초기화 성공"); next()
                }
                override fun onFailure(error: ErrorType) { Log.w(TAG, "tts init failed: $error"); SignalCheck.fallback("음성", "Pleos TTS 초기화 실패($error) → Android 기본 음성으로 대체"); giveUp() }
            })
            tts.addEventListener(events)
        }.onFailure { Log.w(TAG, "Pleos TTS unavailable: $it"); giveUp() }
    }

    private fun giveUp() {
        if (released) return
        failed = true
        SignalCheck.fallback("음성", "Pleos TTS 사용 불가 → Android 기본 음성으로 대체")
        val pending = synchronized(queue) { queue.map { it.first }.also { queue.clear() } }
        onUnavailable(pending)
    }

    override fun speak(text: String, urgent: Boolean) {
        if (released || failed) return
        synchronized(queue) {
            if (urgent) { queue.clear(); runCatching { tts.stop() }; handler.removeCallbacksAndMessages(null); speaking = false }
            queue.addLast(text to System.currentTimeMillis())
            while (queue.size > 16) queue.removeFirst()
        }
        next()
    }

    /** Called from SDK events; also by the watchdog when the SDK never reports the end of an utterance. */
    private fun finished() {
        if (released) return
        handler.removeCallbacksAndMessages(null)
        speaking = false
        next()
    }

    @Synchronized private fun next() {
        if (released || !ready || speaking) return
        val now = System.currentTimeMillis()
        val text = synchronized(queue) {
            var t: String? = null
            while (t == null) {
                val (line, at) = queue.pollFirst() ?: break
                if (now - at > MAX_AGE_MS) Log.w(TAG, "dropping stale line: $line") else t = line
            }
            t
        } ?: return
        speaking = true
        val seq = ++utteranceSeq
        Log.i(TAG, "speak: $text")
        runCatching { tts.speak(text, true) }.onFailure { Log.w(TAG, "speak failed: $it"); speaking = false }
        // Watchdog: if no onDone/onStop arrives, assume the line finished (~0.18 s per character, 2–12 s).
        val budgetMs = (2_000L + text.length * 180L).coerceAtMost(12_000L)
        handler.postDelayed({ if (speaking && seq == utteranceSeq) { Log.w(TAG, "tts end event missing, releasing queue"); finished() } }, budgetMs)
    }

    override fun stop() {
        synchronized(queue) { queue.clear() }
        utteranceSeq++; handler.removeCallbacksAndMessages(null); speaking = false
        runCatching { tts.stop() }
    }
    override fun release() { released = true; ready = false; stop(); runCatching { tts.removeEventListener(events); tts.release() } }

    private companion object { const val TAG = "PleosSpeaker"; const val MAX_AGE_MS = 8_000L }
}
