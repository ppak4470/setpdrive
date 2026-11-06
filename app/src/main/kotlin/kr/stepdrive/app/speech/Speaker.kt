package kr.stepdrive.app.speech

import android.content.Context
import android.speech.tts.TextToSpeech as AndroidTts
import android.util.Log
import java.util.Locale

/** Speaks one short sentence at a time. Urgent lines interrupt; the rest queue up. */
interface Speaker {
    fun speak(text: String, urgent: Boolean = false)
    fun stop()
    fun refreshPermissions() = Unit
    fun release()
}

/** Pleos TTS first; if its service is missing (some emulator images), Android's own TTS takes over seamlessly. */
class FallbackSpeaker(private val context: Context) : Speaker {
    private val androidSpeaker = AndroidSpeaker(context)
    private var pleos: PleosSpeaker? = null

    init { refreshPermissions() }

    override fun refreshPermissions() {
        val granted = context.checkSelfPermission("pleos.car.permission.TTS_SERVICE") == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pleos?.release()
            pleos = null
        } else if (pleos == null) {
            pleos = PleosSpeaker(context) { pending -> pending.forEach { androidSpeaker.speak(it, urgent = false) } }
        }
    }

    override fun speak(text: String, urgent: Boolean) {
        val source: Speaker = pleos?.takeUnless { it.failed } ?: androidSpeaker
        source.speak(text, urgent)
    }
    override fun stop() { pleos?.stop(); androidSpeaker.stop() }
    override fun release() { pleos?.release(); androidSpeaker.release() }
}

/** Fallback for devices without the Pleos speech service (plain emulator, tablet demo). */
class AndroidSpeaker(context: Context) : Speaker {
    @Volatile private var ready = false
    @Volatile private var failed = false
    @Volatile private var released = false
    private val pending = SpeechBacklog()
    private var tts: AndroidTts? = null

    init {
        tts = AndroidTts(context.applicationContext) { status ->
            if (released) return@AndroidTts
            ready = status == AndroidTts.SUCCESS
            if (ready) {
                val lang = tts?.setLanguage(Locale.KOREAN)
                val engine = runCatching { tts?.defaultEngine }.getOrNull()
                Log.i("AndroidSpeaker", "Android TTS ready, engine=$engine, korean=$lang")
                if (lang == AndroidTts.LANG_MISSING_DATA || lang == AndroidTts.LANG_NOT_SUPPORTED)
                    kr.stepdrive.app.diag.SignalCheck.fail("음성", "Android TTS 엔진($engine)에 한국어 음성 데이터 없음 → 소리가 나지 않음")
                else kr.stepdrive.app.diag.SignalCheck.fallback("음성", "Android 기본 음성($engine) 사용 중")
                pending.drain().forEach { speak(it.text, it.urgent) }
            } else {
                failed = true; pending.clear()
                Log.w("AndroidSpeaker", "TTS init failed: $status")
                kr.stepdrive.app.diag.SignalCheck.fail("음성", "Android TTS 엔진 없음(status=$status) → 에뮬레이터에 음성 엔진이 설치되어 있지 않음")
            }
        }
    }

    override fun speak(text: String, urgent: Boolean) {
        if (released || failed) return
        val engine = tts
        if (!ready || engine == null) { pending.add(text, urgent); return }
        engine.speak(text, if (urgent) AndroidTts.QUEUE_FLUSH else AndroidTts.QUEUE_ADD, null, text.hashCode().toString())
    }

    override fun stop() { pending.clear(); runCatching { tts?.stop() } }
    override fun release() { released = true; ready = false; pending.clear(); runCatching { tts?.shutdown() } }
}
