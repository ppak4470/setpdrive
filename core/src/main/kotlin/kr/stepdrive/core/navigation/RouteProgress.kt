package kr.stepdrive.core.navigation

import kr.stepdrive.core.model.*

enum class RoutePhase { IDLE, SEARCHING, WAITING_GUIDANCE, GUIDING, ARRIVED, FAILED, DISCONNECTED }

/** Serially reduced SDK events. A completed request still needs fresh TBT data. */
class RouteProgress {
    var state = NavState()
        private set
    private var phaseStartedAt = 0L
    fun begin(now: Long): NavState = state.copy(status = NavStatus.INACTIVE, maneuvers = emptyList(),
        remainDistanceM = null, remainTimeS = null, accidentProneAheadM = null, speedLimitKmh = null,
        routePolyline = emptyList(), routeGeometryVerified = false,
        routeRevision = state.routeRevision + 1, routePhase = RoutePhase.SEARCHING,
        routeMessage = "경로를 다시 확인하고 있어요", guidanceTMs = now).also { state = it; phaseStartedAt = now }
    fun complete(now: Long): NavState = state.copy(status = NavStatus.INACTIVE, maneuvers = emptyList(),
        routePhase = RoutePhase.WAITING_GUIDANCE, guidanceTMs = now,
        routeMessage = "새 경로 안내를 기다리고 있어요").also { state = it; phaseStartedAt = now }
    fun status(status: NavStatus, now: Long): NavState {
        if (state.routePhase == RoutePhase.SEARCHING) return state
        if (status == NavStatus.FREE_DRIVE) return clear(RoutePhase.IDLE, now).let { update { it.copy(status = status) } }
        return update { it.copy(status = status) }
    }
    fun expire(now: Long): NavState {
        if (state.routePhase in setOf(RoutePhase.SEARCHING, RoutePhase.WAITING_GUIDANCE) && now - phaseStartedAt > 30_000)
            return clear(RoutePhase.FAILED, now, "새 경로 안내를 받지 못했어요. 내비를 확인해 주세요")
        return update { it.freshAt(now) }
    }
    fun guidance(items: List<Maneuver>, now: Long): NavState {
        if (state.routePhase == RoutePhase.SEARCHING || state.routePhase == RoutePhase.FAILED || state.routePhase == RoutePhase.DISCONNECTED) return state
        val safe = items.filter { it.distanceM.isFinite() && it.distanceM >= 0 }.take(200)
        state = state.copy(maneuvers = safe, guidanceTMs = now,
            status = if (safe.isNotEmpty()) NavStatus.ROUTE_DRIVE else state.status,
            routePhase = if (safe.isNotEmpty()) RoutePhase.GUIDING else state.routePhase,
            routeMessage = if (safe.isNotEmpty()) null else state.routeMessage)
        return state
    }
    fun clear(phase: RoutePhase, now: Long, message: String? = null): NavState {
        state = NavState(routePhase = phase, routeRevision = state.routeRevision + 1, routeMessage = message, guidanceTMs = now)
        phaseStartedAt = now
        return state
    }
    fun update(transform: (NavState) -> NavState): NavState = transform(state).also { state = it }
}

/** Uses a genuine navigation-provider route geometry, never a straight line between destinations. */
class RouteDeviationMonitor {
    private var since: Long? = null
    private var revision: Long? = null
    private var lastFixAt: Long? = null
    private var reported = false
    fun tick(nav: NavState, now: Long): Boolean {
        if (revision != nav.routeRevision) { revision = nav.routeRevision; since = null; reported = false; lastFixAt = null }
        val at = nav.location
        val time = nav.locationTMs
        val accuracy = nav.locationAccuracyM
        val points = nav.routePolyline
        fun LatLng.valid() = lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0
        if (!nav.routeGeometryVerified || points.size !in 2..20_000 || at == null || !at.valid() || points.any { !it.valid() } || time == null || accuracy == null ||
            !accuracy.isFinite() || accuracy !in 0.0..25.0 || now - time !in 0..3_000 ||
            nav.status != NavStatus.ROUTE_DRIVE || nav.routePhase == RoutePhase.SEARCHING) {
            since = null; return false
        }
        if (lastFixAt == time) return false
        if (lastFixAt?.let { time - it !in 1..3_000 } == true) since = null
        lastFixAt = time
        val distance = points.zipWithNext().minOf { (a, b) -> kr.stepdrive.core.hazard.Geo.distanceToSegmentM(at, a, b) }
        if (distance <= 60.0 + accuracy) { since = null; reported = false; return false }
        if (since == null) since = time
        if (!reported && time - since!! >= 5_000) { reported = true; return true }
        return false
    }
}
