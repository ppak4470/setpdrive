package kr.stepdrive.app.speech

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Locale
import kotlinx.coroutines.*
import kr.stepdrive.app.diag.SignalCheck

enum class VoiceCommand { NEXT, REPEAT, START, YES, NO, HELP, REROUTE, STOP }

/** Short spoken commands: "다음", "다시", "출발", "응/네", "아니" — nothing free-form while driving. */
interface VoiceCommandSource {
    val commands: SharedFlow<VoiceCommand>
    /** Listen for one utterance (a few seconds), then stop. */
    fun listenOnce()
    fun refreshPermissions() = Unit
    fun release()
}

class NoVoiceCommandSource : VoiceCommandSource {
    override val commands: SharedFlow<VoiceCommand> = MutableSharedFlow()
    override fun listenOnce() = Unit
    override fun release() = Unit
}

object CommandParser {
    fun parse(text: String): VoiceCommand? {
        val t = text.filterNot { it.isWhitespace() || it in ".,!?。！？" }
        // Match complete short commands. Substrings can turn negation or conversation into NEXT.
        return when {
            t in setOf("경로재탐색", "재탐색", "경로다시찾아", "경로다시찾아줘") -> VoiceCommand.REROUTE
            t in setOf("연습종료", "연습끝내기", "연습끝내줘") -> VoiceCommand.STOP
            t in setOf("다음", "다음단계", "다음으로", "넘어가", "다음으로넘어가", "다음단계로넘어가", "다음으로넘어가주세요") -> VoiceCommand.NEXT
            t in setOf("다시", "다시말해", "다시말해줘", "다시말해주세요", "뭐라고", "반복") -> VoiceCommand.REPEAT
            t in setOf("출발", "시작", "출발하자", "시작하자") -> VoiceCommand.START
            t in setOf("도와줘", "도움말", "뭐라고말해야해") -> VoiceCommand.HELP
            t in setOf("아니", "아니요", "싫어", "됐어") -> VoiceCommand.NO
            t in setOf("응", "어", "네", "예", "그래", "좋아") -> VoiceCommand.YES
            else -> null
        }
    }
}

/** Pleos SpeechToText SDK in ON_DEVICE mode. */
class PleosVoiceCommandSource(private val context: Context) : VoiceCommandSource {
    private val _commands = MutableSharedFlow<VoiceCommand>(extraBufferCapacity = 4)
    override val commands: SharedFlow<VoiceCommand> = _commands.asSharedFlow()
    private val stt = SpeechToText(context.applicationContext, Mode.ON_DEVICE)
    @Volatile private var ready = false
    @Volatile private var initializing = false
    @Volatile private var listening = false
    private var generation = 0L
    private var pendingListen = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timeout: Job? = null
    private var listener: ResultListener? = null

    private fun listenerFor(run: Long) = object : ResultListener {
        override fun onUpdated(text: String, completed: Boolean) {
            synchronized(this@PleosVoiceCommandSource) {
                if (!completed || !listening || run != generation) return
                CommandParser.parse(text)?.let { _commands.tryEmit(it); SignalCheck.ok("음성인식", "명령 인식: $it") }
                    ?: SignalCheck.waiting("음성인식", "명령을 확인하지 못했어요. 말하기를 눌러 다시 시도하세요")
                release()
            }
        }
        override fun onUpdatedEpdData(start: Long, end: Long) = Unit
        override fun onStartedRecognition() = Unit
        override fun onEndedRecognition() = Unit
        override fun onError(error: ErrorType) {
            synchronized(this@PleosVoiceCommandSource) {
                if (run != generation) return
                SignalCheck.fail("음성인식", error.toString()); release()
            }
        }
        override fun onReady() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
    }

    init { refreshPermissions() }

    @Synchronized override fun refreshPermissions() {
        val permissions = listOf(android.Manifest.permission.RECORD_AUDIO, "pleos.car.permission.STT_SERVICE")
        if (permissions.any { context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }) { release(); return }
        if (ready || initializing) return
        initializing = true
        val run = ++generation
        armTimeout(run, "음성 서비스 연결 시간이 초과됐어요")
        runCatching {
            listener = listenerFor(run)
            stt.addListener(listener!!)
            stt.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    synchronized(this@PleosVoiceCommandSource) {
                        if (run != generation) return
                        timeout?.cancel()
                        runCatching { stt.setLocale(Locale.KOREA) }.onFailure { Log.w(TAG, "STT locale failed", it) }
                        ready = true; initializing = false
                        SignalCheck.ok("음성인식", "음성 인식 초기화 완료")
                        if (pendingListen) startRecognition()
                    }
                }
                override fun onFailure(error: ErrorType) {
                    synchronized(this@PleosVoiceCommandSource) {
                        if (run != generation) return
                        SignalCheck.fail("음성인식", "음성 서비스 연결 실패: $error"); release()
                    }
                }
            })
        }.onFailure { SignalCheck.fail("음성인식", "음성 서비스를 사용할 수 없어요"); release() }
    }

    @Synchronized override fun listenOnce() {
        if (listening) return
        pendingListen = true
        refreshPermissions()
        if (ready && pendingListen) startRecognition()
    }
    private fun startRecognition() {
        pendingListen = false; listening = true
        armTimeout(generation, "인식 시간이 끝났어요. 말하기를 눌러 다시 시도하세요")
        SignalCheck.waiting("음성인식", "짧은 명령을 기다립니다")
        runCatching { stt.request() }.onFailure { SignalCheck.fail("음성인식", "인식을 시작하지 못했어요"); release() }
    }
    private fun armTimeout(run: Long, message: String) {
        timeout?.cancel()
        timeout = scope.launch {
            delay(10_000)
            synchronized(this@PleosVoiceCommandSource) {
                if (run == generation) { SignalCheck.fail("음성인식", message); release() }
            }
        }
    }
    @Synchronized override fun release() {
        generation++; ready = false; initializing = false; listening = false; pendingListen = false
        timeout?.cancel(); timeout = null
        runCatching { stt.stop() }; runCatching { listener?.let { stt.removeListener(it) }; stt.release() }
        listener = null
    }

    private companion object { const val TAG = "PleosVoice" }
}
