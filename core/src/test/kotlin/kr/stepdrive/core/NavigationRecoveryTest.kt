package kr.stepdrive.core

import kr.stepdrive.core.navigation.*
import kr.stepdrive.core.model.*
import kr.stepdrive.core.hazard.HazardIndex
import kr.stepdrive.core.course.CourseRecommender
import kr.stepdrive.core.score.ScoreEngine
import kotlin.test.*

class NavigationRecoveryTest {
    private fun turn(d: Double = 100.0) = Maneuver(ManeuverType.LEFT, d, "좌회전")
    @Test fun rerouteDropsOldGuidanceUntilCompletedAndFreshTbtArrives() {
        val p = RouteProgress()
        p.guidance(listOf(turn()), 1_000)
        p.begin(2_000)
        assertNull(p.state.next)
        assertNull(p.guidance(listOf(turn(10.0)), 2_100).next)
        assertEquals(RoutePhase.WAITING_GUIDANCE, p.complete(3_000).routePhase)
        assertEquals(RoutePhase.GUIDING, p.guidance(listOf(turn(500.0)), 3_100).routePhase)
        assertEquals(500.0, p.state.next!!.distanceM)
        assertNull(p.state.routeMessage)
    }
    @Test fun failedOrDisconnectedRouteRejectsLateTbt() {
        for (phase in listOf(RoutePhase.FAILED, RoutePhase.DISCONNECTED)) {
            val p = RouteProgress(); p.guidance(listOf(turn()), 1_000); p.clear(phase, 2_000)
            assertNull(p.guidance(listOf(turn()), 2_100).next)
            p.complete(3_000)
            assertNotNull(p.guidance(listOf(turn()), 3_100).next)
        }
    }
    @Test fun locationResponsesCannotKeepRouteSearchAliveForever() {
        val p = RouteProgress(); p.begin(1_000)
        repeat(35) { i -> p.update { it.copy(locationTMs = 1_000L * i) }; p.expire(1_000L * (i + 1)) }
        assertEquals(RoutePhase.FAILED, p.state.routePhase)
        assertNull(p.state.next)
    }
    @Test fun emptyTbtCannotKeepInitialWaitAliveForever() {
        val p = RouteProgress(); p.complete(1_000)
        repeat(35) { i -> p.guidance(emptyList(), 1_000L * (i + 1)); p.expire(1_000L * (i + 1)) }
        assertEquals(RoutePhase.FAILED, p.state.routePhase)
    }
    @Test fun freeDriveEndsWaitButDoesNotCancelInFlightSearch() {
        val p = RouteProgress(); p.complete(1_000)
        assertEquals(RoutePhase.IDLE, p.status(NavStatus.FREE_DRIVE, 2_000).routePhase)
        p.begin(3_000)
        assertEquals(RoutePhase.SEARCHING, p.status(NavStatus.FREE_DRIVE, 3_100).routePhase)
    }
    @Test fun routeReplacementNearTurnDoesNotGradeTheAbandonedTurn() {
        val score = ScoreEngine("reroute", Tier.CITY, HazardIndex(emptyList()))
        score.onTick(VehicleSample(1_000, 30f, Gear.DRIVE, TurnSignal.OFF), NavState(NavStatus.ROUTE_DRIVE, listOf(turn())))
        score.onTick(VehicleSample(2_000, 30f, Gear.DRIVE, TurnSignal.OFF), NavState(NavStatus.ROUTE_DRIVE, listOf(turn(10.0))))
        score.onTick(VehicleSample(3_000, 30f, Gear.DRIVE, TurnSignal.OFF), NavState(NavStatus.ROUTE_DRIVE, listOf(turn(500.0)), routeRevision = 1))
        assertNull(score.finish(4_000).signalRate)
    }
    @Test fun checkpointDoesNotConsumePendingTurnOrGrantXp() {
        val score = ScoreEngine("checkpoint", Tier.CITY, HazardIndex(emptyList()))
        score.onTick(VehicleSample(1_000, 30f, Gear.DRIVE, TurnSignal.LEFT), NavState(NavStatus.ROUTE_DRIVE, listOf(turn())))
        score.onTick(VehicleSample(2_000, 30f, Gear.DRIVE, TurnSignal.LEFT), NavState(NavStatus.ROUTE_DRIVE, listOf(turn(10.0))))
        repeat(3) { assertEquals(0, score.checkpoint(2_100 + it.toLong()).xp) }
        assertEquals(1, score.finish(3_000).events.count { it.kind == TripEvent.Kind.SIGNAL_OK })
    }
    private fun route(t: Long) = NavState(NavStatus.ROUTE_DRIVE, location = LatLng(37.01, 127.005), locationTMs = t,
        routePolyline = listOf(LatLng(37.0, 127.0), LatLng(37.0, 127.01)), routeGeometryVerified = true, locationAccuracyM = 5.0)
    @Test fun sustainedDepartureRequestsOnceAndRearmsAfterReturning() {
        val m = RouteDeviationMonitor()
        for (t in 1_000L..5_000L step 1_000) assertFalse(m.tick(route(t), t))
        assertTrue(m.tick(route(6_000), 6_000))
        assertFalse(m.tick(route(7_000), 7_000))
        assertFalse(m.tick(route(8_000).copy(location = LatLng(37.0, 127.005)), 8_000))
        for (t in 9_000L..13_000L step 1_000) assertFalse(m.tick(route(t), t))
        assertTrue(m.tick(route(14_000), 14_000))
    }
    @Test fun inaccurateStaleOrUnverifiedGeometryCannotTriggerDeviation() {
        val variants: List<(NavState) -> NavState> = listOf(
            { it.copy(routeGeometryVerified = false) }, { it.copy(locationAccuracyM = 40.0) },
            { it.copy(locationTMs = 0) }, { it.copy(location = LatLng(Double.NaN, 127.0)) },
            { it.copy(routePolyline = listOf(LatLng(91.0, 127.0), LatLng(37.0, 127.0))) })
        for (variant in variants) {
            val m = RouteDeviationMonitor()
            for (t in 5_000L..20_000L step 1_000) assertFalse(m.tick(variant(route(t)), t))
        }
    }
    @Test fun repeatedFixDoesNotCountAsSustainedDeparture() {
        val m = RouteDeviationMonitor()
        for (t in 1_000L..20_000L step 100) assertFalse(m.tick(route(1_000), t))
    }
    @Test fun unknownRoadDetailsCannotBecomeSafeRouteRecommendation() {
        val c = CourseCandidate("destination", "목적지", "목적지", 0, 0, listOf(LatLng(37.0, 127.0)), RouteFeatures(), Tier.CITY, false)
        val evaluated = CourseRecommender(HazardIndex(emptyList())).evaluate(listOf(c), Tier.CITY).single()
        assertFalse(evaluated.recommended)
        assertTrue(evaluated.reason.contains("알 수 없어요"))
    }
    @Test fun twelveHoursOfSamplesAndEventsKeepBoundedDetailAndFullCounters() {
        val score = ScoreEngine("long", Tier.CITY, HazardIndex(emptyList()))
        for (second in 1..43_200) {
            score.onTick(VehicleSample(second * 1_000L, 36f, Gear.DRIVE), NavState())
            if (second % 10 == 0) score.note(TripEvent.Kind.HARSH_BRAKE, second * 1_000L, "관측")
        }
        val result = score.finish(43_200_000)
        assertEquals(2_000, result.events.size)
        assertTrue(result.eventsTruncated)
        assertEquals(4_320 / result.distanceKm * 10, result.harshBrakePer10Km, 0.00001)
        assertEquals(431.99, result.distanceKm, 0.01)
    }
}
