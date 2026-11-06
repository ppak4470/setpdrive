package kr.stepdrive.core

import kr.stepdrive.core.hazard.HazardIndex
import kr.stepdrive.core.model.*
import kr.stepdrive.core.score.ScoreEngine
import org.junit.Test
import kotlin.test.*

class TripDistanceTest {
    private fun engine() = ScoreEngine("distance", Tier.CITY, HazardIndex(emptyList()))

    @Test fun frozenOdometerUsesObservedSpeedWithoutCountingOtherSignalsTwice() {
        val e = engine()
        for (second in 0..120) {
            val s = VehicleSample(1_000L + second * 1_000, 36f, Gear.DRIVE, odometerKm = 0f)
            e.onTick(s, NavState())
            e.onTick(s.copy(tMs = s.tMs + 20, brakePressed = false), NavState())
        }
        val result = e.finish(121_020)
        assertEquals(1.2, result.distanceKm, 0.001)
        assertTrue(result.distanceEstimated)
        assertFalse(result.provisional)
    }

    @Test fun missingSpeedIntervalsAreNotInventedAsDistance() {
        val e = engine()
        listOf(
            VehicleSample(1_000, 36f, Gear.DRIVE),
            VehicleSample(2_000, 36f, Gear.DRIVE),
            VehicleSample(6_000, 36f, Gear.DRIVE, speedTMs = 2_000),
            VehicleSample(9_000, 36f, Gear.DRIVE),
            VehicleSample(10_000, 36f, Gear.DRIVE),
        ).forEach { e.onTick(it, NavState()) }
        assertEquals(0.02, e.finish(10_000).distanceKm, 0.0001)
    }

    @Test fun changingOdometerKeepsTheMeasuredDistance() {
        val e = engine()
        e.onTick(VehicleSample(1_000, 36f, Gear.DRIVE, odometerKm = 100f), NavState())
        e.onTick(VehicleSample(2_000, 36f, Gear.DRIVE, odometerKm = 102f), NavState())
        val result = e.finish(2_000)
        assertEquals(2.0, result.distanceKm, 0.001)
        assertFalse(result.distanceEstimated)
    }

    @Test fun stationaryTripDoesNotInventOneHundredMetres() {
        val e = engine()
        e.onTick(VehicleSample(1_000, 0f, Gear.PARK), NavState())
        e.onTick(VehicleSample(2_000, 0f, Gear.PARK), NavState())
        val result = e.finish(2_000)
        assertEquals(0.0, result.distanceKm)
        assertEquals(0, result.xp)
    }
}
