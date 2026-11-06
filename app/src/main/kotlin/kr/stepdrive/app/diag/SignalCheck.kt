package kr.stepdrive.app.diag

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Emulator self-check log. Every signal the app depends on reports 정상 / 실패 / 대기 in Korean under one tag,
 * and a one-line summary is printed periodically, so a tester can read the result without knowing the code.
 *
 *   adb logcat -s STEPCHECK:*
 */
object SignalCheck {
    private const val TAG = "STEPCHECK"
    private const val SUMMARY_EVERY_MS = 15_000L

    enum class State(val label: String) { WAITING("대기"), OK("정상"), FAIL("실패"), FALLBACK("대체") }

    /** Display order of the summary line. */
    private val order = listOf("차량서비스", "속도", "기어", "방향지시등", "핸들", "브레이크", "주행거리", "내비연결", "내비안내", "경로요청", "목적지", "음성", "코칭")
    /** 2차 준비 기능 (앱 0.2.x): printed as a second summary line. */
    private val order2 = listOf("코스후보", "모의내비", "알림서비스", "세션유지", "회전코칭", "참고안내", "주차체크", "기어안내", "피드백단계", "주행요약")

    private val states = LinkedHashMap<String, State>().apply { (order + order2).forEach { put(it, State.WAITING) } }
    private val details = HashMap<String, String>()

    @Synchronized
    fun ok(name: String, detail: String = "") {
        if (states[name] == State.OK && details[name] == detail) return
        val first = states[name] != State.OK
        states[name] = State.OK; details[name] = detail
        // The first OK is always printed; later OKs only when the detail changed (progress of a feature).
        if (first || name in order2) Log.i(TAG, "✔ $name 정상" + (if (detail.isNotEmpty()) " ($detail)" else ""))
    }

    /** Puts an item back to 대기 (e.g. a new session starts) so progress shows again. */
    @Synchronized
    fun reset(name: String, hint: String = "") { states[name] = State.WAITING; details[name] = hint }

    @Synchronized
    fun fail(name: String, reason: String) {
        states[name] = State.FAIL; details[name] = reason
        Log.e(TAG, "✘ $name 실패: $reason")
    }

    @Synchronized
    fun fallback(name: String, reason: String) {
        if (states[name] == State.OK) return
        states[name] = State.FALLBACK; details[name] = reason
        Log.w(TAG, "△ $name 대체: $reason")
    }

    @Synchronized
    fun waiting(name: String, hint: String) {
        if (states[name] != State.WAITING) return
        details[name] = hint
    }

    @Synchronized
    private fun summary(): String = order.joinToString(" | ") { n -> "$n ${states[n]?.label}" }

    @Synchronized
    private fun summary2(): String = order2.joinToString(" | ") { n -> "$n ${states[n]?.label}" }

    @Synchronized
    private fun waitingHints(): List<String> = (order + order2).filter { states[it] == State.WAITING && details[it]?.isNotEmpty() == true }.map { "$it: ${details[it]}" }

    fun startSummary(scope: CoroutineScope) {
        scope.launch {
            delay(5_000)
            while (isActive) {
                Log.i(TAG, "===== 점검 요약 =====")
                Log.i(TAG, summary())
                Log.i(TAG, "[2차 기능] " + summary2())
                waitingHints().forEach { Log.i(TAG, "  대기 중 · $it") }
                delay(SUMMARY_EVERY_MS)
            }
        }
    }
}
