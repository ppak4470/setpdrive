package kr.stepdrive.app.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kr.stepdrive.core.demo.DemoFrame
import kr.stepdrive.core.demo.DemoScenario

/**
 * Plays the scripted drive one frame per second so the vehicle and navigation demo sources stay in sync.
 * Used on the Pleos emulator / any Android device when real signals are unavailable, and for the 2nd-round demo.
 */
class DemoClock(private val scope: CoroutineScope, private val frameMs: Long = 1_000) {
    private val frames: List<DemoFrame> = DemoScenario.frames()
    private val _frame = MutableStateFlow(frames.first())
    val frame: StateFlow<DemoFrame> = _frame.asStateFlow()
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    private var job: Job? = null
    private var index = 0

    val progress: Float get() = index.toFloat() / (frames.size - 1)

    fun play() {
        if (job?.isActive == true) return
        _playing.value = true
        job = scope.launch {
            while (isActive && index < frames.size - 1) {
                delay(frameMs)
                index++
                _frame.value = frames[index]
            }
            _playing.value = false
        }
    }

    fun pause() { job?.cancel(); job = null; _playing.value = false }

    fun restart() { pause(); index = 0; _frame.value = frames[0] }

    fun jumpTo(caption: String) {
        val i = frames.indexOfFirst { it.caption == caption }
        if (i >= 0) { index = i; _frame.value = frames[i] }
    }
}
