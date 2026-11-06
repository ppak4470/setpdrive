package kr.stepdrive.core.model

import kotlinx.serialization.Serializable

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

enum class TurnSignal { UNKNOWN, OFF, LEFT, RIGHT }

/**
 * One snapshot of the vehicle signals the app reads.
 * Unavailable signals are null or UNKNOWN and are excluded from evaluation.
 */
data class VehicleSample(
    val tMs: Long,
    val speedKmh: Float? = null,
    val gear: Gear = Gear.UNKNOWN,
    val turnSignal: TurnSignal = TurnSignal.UNKNOWN,
    val brakePressed: Boolean? = null,
    /** Steering wheel angle in degrees. The sign is source-specific; only magnitude is interpreted. */
    val steeringAngleDeg: Float? = null,
    val odometerKm: Float? = null,
    /** Time of the speed observation, preserved when another vehicle signal changes. */
    val speedTMs: Long = tMs,
)

/** A speed subscription is continuous; silence must not keep an old moving/stopped value alive. */
fun VehicleSample.freshAt(nowMs: Long = tMs, maxSpeedAgeMs: Long = 3_000): VehicleSample =
    if (speedKmh != null && (!speedKmh.isFinite() || nowMs - speedTMs !in 0..maxSpeedAgeMs))
        copy(speedKmh = null) else this

@Serializable
data class LatLng(val lat: Double, val lng: Double)

enum class ManeuverType { LEFT, RIGHT, MERGE_LEFT, MERGE_RIGHT, EXIT, EXIT_LEFT, EXIT_RIGHT, UTURN, STRAIGHT, ARRIVE, OTHER }

/** One turn-by-turn instruction from the navigation (first item = the next maneuver). */
data class Maneuver(val type: ManeuverType, val distanceM: Double, val description: String = "")

enum class NavStatus { INACTIVE, FREE_DRIVE, ROUTE_DRIVE }

data class NavState(
    val status: NavStatus = NavStatus.INACTIVE,
    val maneuvers: List<Maneuver> = emptyList(),
    val remainDistanceM: Int? = null,
    val remainTimeS: Int? = null,
    val location: LatLng? = null,
    val headingDeg: Double? = null,
    val speedLimitKmh: Int? = null,
    /** Distance to the next accident-prone caution point reported by the navigation, if any. */
    val accidentProneAheadM: Int? = null,
    /** Null for deterministic demo/test sources; real adapters stamp received observations. */
    val guidanceTMs: Long? = null,
    val locationTMs: Long? = null,
    val routeRevision: Long = 0,
    val routePhase: kr.stepdrive.core.navigation.RoutePhase = kr.stepdrive.core.navigation.RoutePhase.IDLE,
    val routeMessage: String? = null,
    val routePolyline: List<LatLng> = emptyList(),
    val routeGeometryVerified: Boolean = false,
    val locationAccuracyM: Double? = null,
) {
    val next: Maneuver? get() = maneuvers.firstOrNull()
}

fun NavState.freshAt(nowMs: Long, maxAgeMs: Long = 10_000): NavState {
    val staleGuidance = guidanceTMs?.let { nowMs - it !in 0..maxAgeMs } == true ||
        routePhase in setOf(kr.stepdrive.core.navigation.RoutePhase.SEARCHING, kr.stepdrive.core.navigation.RoutePhase.FAILED,
            kr.stepdrive.core.navigation.RoutePhase.DISCONNECTED, kr.stepdrive.core.navigation.RoutePhase.WAITING_GUIDANCE)
    val stalePosition = locationTMs?.let { nowMs - it !in 0..maxAgeMs } == true
    return copy(
        status = if (staleGuidance) NavStatus.INACTIVE else status,
        maneuvers = if (staleGuidance) emptyList() else maneuvers,
        remainDistanceM = if (staleGuidance) null else remainDistanceM,
        remainTimeS = if (staleGuidance) null else remainTimeS,
        accidentProneAheadM = if (staleGuidance) null else accidentProneAheadM,
        speedLimitKmh = if (staleGuidance) null else speedLimitKmh,
        location = if (stalePosition) null else location,
        headingDeg = if (stalePosition) null else headingDeg,
        routeMessage = if (staleGuidance && routePhase == kr.stepdrive.core.navigation.RoutePhase.GUIDING) "내비 안내 수신이 지연되고 있어요" else routeMessage,
    )
}

val ManeuverType.requiredSignal: TurnSignal?
    get() = when (this) {
        ManeuverType.LEFT, ManeuverType.MERGE_LEFT, ManeuverType.EXIT_LEFT, ManeuverType.UTURN -> TurnSignal.LEFT
        ManeuverType.RIGHT, ManeuverType.MERGE_RIGHT, ManeuverType.EXIT_RIGHT -> TurnSignal.RIGHT
        // A generic/central exit has no reliable left/right requirement.
        else -> null
    }
