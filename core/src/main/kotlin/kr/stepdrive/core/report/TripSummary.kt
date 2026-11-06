package kr.stepdrive.core.report

import kr.stepdrive.core.model.TripEvent
import kr.stepdrive.core.model.TripScore

/** Short spoken/visible recap of the last trip: score and the single biggest deduction. */
object TripSummary {
    data class Deduction(val label: String, val points: Int, val detail: String)

    /** Deductions in the same shape as the score engine (see the proposal's scoring table). */
    fun deductions(t: TripScore): List<Deduction> {
        val out = mutableListOf<Deduction>()
        t.signalRate?.let { r ->
            val missed = t.events.count { it.kind == TripEvent.Kind.SIGNAL_MISSED }
            val pts = ((1 - r) * 30).toInt()
            if (pts > 0) out += Deduction("깜빡이 미사용", pts, "회전 ${missed}번에서 깜빡이를 안 켰어요")
        }
        val brakes = t.events.count { it.kind == TripEvent.Kind.HARSH_BRAKE }
        val brakePts = (t.harshBrakePer10Km * 6).coerceAtMost(25.0).toInt()
        if (brakePts > 0) out += Deduction("급제동", brakePts, "급제동 ${brakes}번")
        val accels = t.events.count { it.kind == TripEvent.Kind.HARSH_ACCEL }
        val accelPts = (t.harshAccelPer10Km * 4).coerceAtMost(15.0).toInt()
        if (accelPts > 0) out += Deduction("급가속", accelPts, "급가속 ${accels}번")
        val speedPts = (t.speedingRatio * 40).coerceAtMost(20.0).toInt()
        if (speedPts > 0) out += Deduction("제한속도 초과", speedPts, "주행의 ${(t.speedingRatio * 100).toInt()}%를 제한속도보다 빠르게 달렸어요")
        t.hazardSafeRatio?.let { r ->
            val pts = ((1 - r) * 10).toInt()
            if (pts > 0) out += Deduction("사고다발 구간 고속 진입", pts, "위험 구간을 빠르게 진입했어요")
        }
        return out.sortedByDescending { it.points }
    }

    fun biggest(t: TripScore): Deduction? = deductions(t).firstOrNull()

    /** One sentence for the home screen and the voice greeting. */
    fun sentence(t: TripScore): String {
        val b = biggest(t)
        return when {
            t.provisional -> "지난 주행은 평가할 회전이 적어 점수를 보류했어요"
            b == null -> "지난 주행 ${t.rawScore}점, 감점 없이 잘 달렸어요"
            else -> "지난 주행 ${t.rawScore}점, 가장 큰 감점은 ${b.label} ${b.points}점이에요. ${b.detail}"
        }
    }
}
