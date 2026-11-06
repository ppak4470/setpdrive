package kr.stepdrive.core

import kr.stepdrive.core.model.*
import kr.stepdrive.core.score.ScoreEngine
import kr.stepdrive.core.hazard.HazardIndex
import kotlin.test.*
import kr.stepdrive.core.parking.ParkingCoach
import kr.stepdrive.core.parking.ParkingStep

class FreshnessTest {
    @Test fun missingSpeedCannotAdvanceOrCompleteTheParkingChecklist() {
        val p = ParkingCoach()
        p.tick(VehicleSample(1_000, 2f, Gear.REVERSE))
        assertEquals(ParkingStep.TURN_REVERSE, p.step)
        p.tick(VehicleSample(2_000, null, Gear.REVERSE))
        p.tick(VehicleSample(10_000, null, Gear.REVERSE))
        assertEquals(ParkingStep.TURN_REVERSE, p.step)
        p.next(); p.next()
        assertEquals(ParkingStep.CHECK, p.step)
        p.tick(VehicleSample(11_000, null, Gear.PARK))
        assertEquals(ParkingStep.CHECK, p.step)
        p.tick(VehicleSample(12_000, 0f, Gear.PARK))
        assertEquals(ParkingStep.DONE, p.step)
    }
    @Test fun recentSpeedSurvivesButExpiredOrInvalidSpeedIsUnavailable() {
        val s = VehicleSample(1_000, 30f, Gear.DRIVE)
        assertEquals(30f, s.freshAt(4_000).speedKmh)
        assertNull(s.freshAt(4_001).speedKmh)
        assertNull(s.copy(speedKmh = Float.NaN).freshAt().speedKmh)
        assertNull(s.copy(speedKmh = Float.POSITIVE_INFINITY).freshAt().speedKmh)
        assertNull(s.freshAt(900).speedKmh)
    }
    @Test fun oldNavigationCannotCompleteATurnOrSupplyALocation() {
        val n = NavState(NavStatus.ROUTE_DRIVE, listOf(Maneuver(ManeuverType.RIGHT, 20.0)),
            location = LatLng(37.5,127.0), speedLimitKmh = 50, guidanceTMs = 1_000, locationTMs = 1_000)
        val expired = n.freshAt(11_001)
        assertEquals(NavStatus.INACTIVE, expired.status)
        assertTrue(expired.maneuvers.isEmpty())
        assertNull(expired.location)
        assertNull(expired.speedLimitKmh)
        val e = ScoreEngine("stale-nav", Tier.CITY, HazardIndex(emptyList()))
        e.onTick(VehicleSample(1_000, 30f, Gear.DRIVE, TurnSignal.OFF), n.copy(maneuvers = listOf(Maneuver(ManeuverType.RIGHT, 100.0))))
        e.onTick(VehicleSample(11_001, 30f, Gear.DRIVE, TurnSignal.OFF), n)
        assertNull(e.finish(12_000).signalRate)
    }
    @Test fun staleSpeedDoesNotCreateHarshBrakingOrSpeeding() {
        val e = ScoreEngine("stale-speed", Tier.CITY, HazardIndex(emptyList()))
        e.onTick(VehicleSample(1_000, 70f, Gear.DRIVE), NavState())
        e.onTick(VehicleSample(5_000, 70f, Gear.DRIVE, speedTMs = 1_000), NavState(speedLimitKmh = 30))
        val result = e.finish(6_000)
        assertTrue(result.events.isEmpty())
        assertEquals(0.0, result.speedingRatio)
    }
}
