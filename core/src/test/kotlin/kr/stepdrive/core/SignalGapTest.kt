package kr.stepdrive.core

import kr.stepdrive.core.coach.DistanceSmoother
import kr.stepdrive.core.hazard.HazardIndex
import kr.stepdrive.core.model.*
import kr.stepdrive.core.score.ScoreEngine
import kotlin.test.*

class SignalGapTest {
    private fun engine() = ScoreEngine("gap", Tier.CITY, HazardIndex(emptyList()))

    @Test fun repeatedNonSpeedEventsDoNotChangeTheSpeedingRatio() {
        val e = engine()
        val nav = NavState(speedLimitKmh = 50)
        val fast = VehicleSample(1_000, 70f, Gear.DRIVE)
        e.onTick(fast, nav)
        repeat(10) { e.onTick(fast.copy(tMs = 1_001L + it, steeringAngleDeg = it.toFloat()), nav) }
        e.onTick(VehicleSample(2_000, 40f, Gear.DRIVE), nav)
        assertEquals(0.5, e.finish(3_000).speedingRatio)
    }

    @Test fun unknownSpeedLimitDoesNotDiluteTheSpeedingRatio() {
        val e = engine()
        e.onTick(VehicleSample(1_000, 70f, Gear.DRIVE), NavState(speedLimitKmh = 50))
        e.onTick(VehicleSample(2_000, 40f, Gear.DRIVE), NavState())
        assertEquals(1.0, e.finish(3_000).speedingRatio)
    }

    @Test fun noSpeedOrManeuverEvidenceDoesNotAwardXpOnALongTrip() {
        val e = engine()
        e.onTick(VehicleSample(1_000, odometerKm = 10f), NavState())
        e.onTick(VehicleSample(2_000, odometerKm = 12f), NavState())
        val result = e.finish(3_000)
        assertTrue(result.provisional)
        assertEquals(0, result.xp)
    }

    @Test fun oldSpeedDoesNotKeepMovingTheEstimatedRoute() {
        val smoother = DistanceSmoother()
        val nav = NavState(NavStatus.ROUTE_DRIVE, listOf(Maneuver(ManeuverType.RIGHT, 300.0)))
        smoother.apply(nav, VehicleSample(0, 36f, Gear.DRIVE))
        smoother.apply(nav, VehicleSample(3_000, 36f, Gear.DRIVE, speedTMs = 0))
        val afterGap = smoother.apply(nav, VehicleSample(6_000, 36f, Gear.DRIVE, speedTMs = 0))
        assertEquals(270.0, afterGap.next!!.distanceM, 0.001)
    }

    @Test fun unknownEntrySpeedIsNotReportedAsASafeHazardPass() {
        val place = LatLng(37.5, 127.0)
        val e = ScoreEngine("hazard-gap", Tier.CITY, HazardIndex(listOf(Hazard("h", "test", place))))
        e.onTick(VehicleSample(1_000, null, Gear.DRIVE), NavState(location = place))
        e.onTick(VehicleSample(2_000, 20f, Gear.DRIVE), NavState(location = place))
        val result = e.finish(3_000)
        assertNull(result.hazardSafeRatio)
        assertTrue(result.events.none { it.kind == TripEvent.Kind.HAZARD_SAFE })
    }
}
