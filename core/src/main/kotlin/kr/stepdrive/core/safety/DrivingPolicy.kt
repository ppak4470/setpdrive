package kr.stepdrive.core.safety

import kr.stepdrive.core.model.*
import kotlin.math.abs

object DrivingPolicy {
    fun restricted(platformRestricted: Boolean, sample: VehicleSample, now: Long = sample.tMs): Boolean {
        val s = sample.freshAt(now)
        return platformRestricted || s.gear != Gear.PARK || s.speedKmh == null || abs(s.speedKmh) > 0.5f
    }
}

enum class ObservationOrigin { VEHICLE_SENSOR, CALIBRATED_VISION, TEST }
enum class LateralDirection { LEFT, RIGHT }
data class PerceptionObservation(
    val observedAtMs: Long,
    val origin: ObservationOrigin,
    val confidence: Double,
    val laneDeparture: LateralDirection? = null,
    val completedLaneChange: LateralDirection? = null,
    val collisionWarning: Boolean? = null,
    val parkingAligned: Boolean? = null,
)
enum class PerceptionAlertKind { LANE_DEPARTURE, OPPOSITE_LANE_CHANGE, COLLISION, PARKING_ALIGNMENT }
data class PerceptionAlert(val kind: PerceptionAlertKind, val text: String)

/** Only supplied, fresh sensor evidence may assert a physical event. Steering angle is not evidence. */
class PerceptionMonitor(private val allowTestInput: Boolean = false) {
    private var lastAt: Long? = null
    private val active = mutableSetOf<PerceptionAlertKind>()
    fun tick(observation: PerceptionObservation?, sample: VehicleSample, navigation: NavState): List<PerceptionAlert> {
        val s = sample.freshAt()
        val o = observation ?: return emptyList()
        if (o.origin == ObservationOrigin.TEST && !allowTestInput ||
            !o.confidence.isFinite() || o.confidence !in 0.95..1.0 || s.tMs - o.observedAtMs !in 0..1_000 ||
            lastAt?.let { o.observedAtMs <= it } == true) return emptyList()
        lastAt = o.observedAtMs
        val now = mutableMapOf<PerceptionAlertKind, String>()
        if (s.gear == Gear.REVERSE && s.speedKmh != null && abs(s.speedKmh) <= 5f && o.parkingAligned == false)
            now[PerceptionAlertKind.PARKING_ALIGNMENT] = "주차 정렬 확인이 필요하다는 신호가 있어요. 주변을 살피세요"
        if (s.speedKmh != null && abs(s.speedKmh) > 0.5f && s.gear in setOf(Gear.DRIVE, Gear.REVERSE)) {
            if (o.collisionWarning == true) now[PerceptionAlertKind.COLLISION] = "충돌 위험 신호가 감지됐어요. 주변을 확인하세요"
            if (s.gear == Gear.DRIVE && o.laneDeparture != null)
                now[PerceptionAlertKind.LANE_DEPARTURE] = "차로 이탈 신호가 감지됐어요. 주변을 확인하세요"
            val nav = navigation.freshAt(s.tMs)
            val need = nav.next?.takeIf { it.distanceM in 0.0..200.0 }?.type?.requiredSignal
            val wrong = when (o.completedLaneChange) {
                LateralDirection.LEFT -> need == TurnSignal.RIGHT
                LateralDirection.RIGHT -> need == TurnSignal.LEFT
                null -> false
            }
            if (s.gear == Gear.DRIVE && nav.status == NavStatus.ROUTE_DRIVE && wrong)
                now[PerceptionAlertKind.OPPOSITE_LANE_CHANGE] = "안내와 다른 방향의 차로 이동이 감지됐어요. 확인하세요"
        }
        val alerts = now.filterKeys { it !in active }.map { PerceptionAlert(it.key, it.value) }
        active.clear(); active.addAll(now.keys)
        return alerts.sortedBy { if (it.kind == PerceptionAlertKind.COLLISION) 0 else 1 }
    }
}
