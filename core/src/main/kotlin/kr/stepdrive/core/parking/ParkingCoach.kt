package kr.stepdrive.core.parking

import kr.stepdrive.core.model.Gear
import kr.stepdrive.core.model.VehicleSample
import kr.stepdrive.core.model.freshAt
import kotlin.math.abs

/**
 * Parking guidance as a checklist, not as steering instructions: the app does not know the bay or the
 * obstacles, so it tells the driver what to check at each stage and reads the car's own signals only to
 * move the checklist along. The camera stays the car's own screen.
 */
enum class ParkingStep(val index: Int, val short: String, val spoken: String) {
    ALIGN(1, "주변 확인", "주차 전에 사이드미러와 후방 카메라로 보행자와 장애물을 확인하세요"),
    TURN_REVERSE(2, "진입 준비 · 천천히 후진", "천천히 후진하며 가이드선을 보세요. 핸들은 공간에 맞게 돌리세요"),
    STRAIGHTEN(3, "차량 위치 확인", "양쪽 간격이 비슷한지, 뒤 공간이 남았는지 확인하세요"),
    CHECK(4, "마무리 점검", "멈추고 기어를 P에 두세요. 주변을 한 번 더 확인하세요"),
    DONE(5, "주차 완료", "주차를 마쳤어요. 수고했어요"),
}

data class ParkingPrompt(val step: ParkingStep, val text: String, val isWarning: Boolean = false)

/**
 * Reads only gear, speed and steering angle. The steering angle is reference information for the driver
 * (absolute angle in degrees) and is never used to claim a direction or aligned road wheels.
 * Steps advance on vehicle signals, or by voice: "다음" (next) / "다시" (repeat).
 * All thresholds are tunable and are meant to be adjusted during testing.
 */
class ParkingCoach(
    private val maxSteeringDeg: Float = 540f,
    private val reverseSpeedWarnKmh: Float = 5f,
    private val turnedRatio: Float = 0.5f,
    private val releasedRatio: Float = 0.3f,
    private val stopHoldMs: Long = 1_500,
    private val warnGapMs: Long = 4_000,
) {
    var step: ParkingStep = ParkingStep.ALIGN
        private set
    private var stoppedSince: Long? = null
    private var lastWarnAt: Long? = null
    private var announced: ParkingStep? = null
    private var wasTurned = false

    fun reset() { step = ParkingStep.ALIGN; stoppedSince = null; announced = null; wasTurned = false; lastWarnAt = null }

    /** Feed each sample; returns prompts to speak (step text when a step starts, warnings when needed). */
    fun tick(sample: VehicleSample): List<ParkingPrompt> {
        val s = sample.freshAt()
        val out = mutableListOf<ParkingPrompt>()
        if (s.gear != Gear.REVERSE) {
            when {
                step == ParkingStep.DONE -> return emptyList()
                // Finishing: the driver put the car in Park during the final check.
                step == ParkingStep.CHECK && s.gear == Gear.PARK -> {
                    if (s.speedKmh?.let { abs(it) <= 0.5f } == true) advance(out)
                    return out
                }
                // Leaving reverse earlier resets the checklist; no prompt.
                step != ParkingStep.ALIGN -> reset()
            }
            return out
        }
        val speed = s.speedKmh?.let { abs(it) } ?: run { stoppedSince = null; return out }
        val angle = s.steeringAngleDeg?.takeIf { it.isFinite() }
        val ratio = angle?.let { abs(it) / maxSteeringDeg }

        if (announced != step) { announced = step; out += ParkingPrompt(step, step.spoken) }

        if (step != ParkingStep.DONE) {
            if (speed > reverseSpeedWarnKmh && warnAllowed(s.tMs)) {
                out += ParkingPrompt(step, "조금 더 천천히", isWarning = true)
            }
        }

        when (step) {
            ParkingStep.ALIGN -> if (speed > 0.5f) advance(out)
            ParkingStep.TURN_REVERSE -> {
                if (ratio != null && ratio >= turnedRatio) wasTurned = true
                val turnedAndReleased = wasTurned && ratio != null && ratio <= releasedRatio
                if (turnedAndReleased || stoppedFor(s.tMs, speed)) advance(out)
            }
            ParkingStep.STRAIGHTEN -> if (stoppedFor(s.tMs, speed)) {
                advance(out)
                angle?.let { out += ParkingPrompt(step, "핸들 회전량은 ${abs(it).toInt()}도예요. 참고하세요") }
            }
            ParkingStep.CHECK, ParkingStep.DONE -> Unit
        }
        return out
    }

    /** Voice command "다음". */
    fun next(): ParkingPrompt? {
        // Completion requires a Park signal, even when the checklist is advanced by voice.
        if (step == ParkingStep.CHECK || step == ParkingStep.DONE) return null
        val out = mutableListOf<ParkingPrompt>()
        advance(out)
        return out.lastOrNull()
    }

    /** Voice command "다시". */
    fun repeat(): ParkingPrompt = ParkingPrompt(step, step.spoken)

    val isDone: Boolean get() = step == ParkingStep.DONE

    private fun stoppedFor(tMs: Long, speed: Float): Boolean {
        if (speed < 0.5f) {
            if (stoppedSince == null) stoppedSince = tMs
            return tMs - stoppedSince!! >= stopHoldMs
        }
        stoppedSince = null
        return false
    }

    private fun warnAllowed(tMs: Long): Boolean {
        val ok = lastWarnAt?.let { tMs - it >= warnGapMs } ?: true
        if (ok) lastWarnAt = tMs
        return ok
    }

    private fun advance(out: MutableList<ParkingPrompt>) {
        step = ParkingStep.entries.getOrNull(step.ordinal + 1) ?: ParkingStep.DONE
        stoppedSince = null
        wasTurned = false
        announced = step
        out += ParkingPrompt(step, step.spoken)
    }
}
