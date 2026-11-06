package kr.stepdrive.core

import kr.stepdrive.core.safety.*
import kr.stepdrive.core.model.*
import kotlin.test.*

class PerceptionSafetyTest {
    @Test fun parkingMisalignmentNeedsTrustedEvidenceAndAlignmentIsNotCompletion() {
        val monitor = PerceptionMonitor()
        val parkedReverse = sample().copy(gear = Gear.REVERSE, speedKmh = 0f)
        val frame = evidence().copy(collisionWarning = false, completedLaneChange = null, parkingAligned = false)
        assertEquals(PerceptionAlertKind.PARKING_ALIGNMENT, monitor.tick(frame, parkedReverse, NavState()).single().kind)
        assertTrue(monitor.tick(frame.copy(observedAtMs = 2_000, parkingAligned = true),
            VehicleSample(2_000, 0f, Gear.REVERSE), NavState()).isEmpty())
    }
    private fun sample(t: Long = 1_000) = VehicleSample(t, 30f, Gear.DRIVE)
    private val nav = NavState(NavStatus.ROUTE_DRIVE, listOf(Maneuver(ManeuverType.MERGE_LEFT, 100.0)))
    private fun evidence(t: Long = 1_000) = PerceptionObservation(t, ObservationOrigin.VEHICLE_SENSOR, 0.99,
        completedLaneChange = LateralDirection.RIGHT, collisionWarning = true)
    @Test fun collisionHasPriorityAndWrongLaneUsesSensorEvidence() {
        val events = PerceptionMonitor().tick(evidence(), sample(), nav)
        assertEquals(listOf(PerceptionAlertKind.COLLISION, PerceptionAlertKind.OPPOSITE_LANE_CHANGE), events.map { it.kind })
    }
    @Test fun absentSensorOrSteeringAloneDoesNotAssertLaneChange() {
        assertTrue(PerceptionMonitor().tick(null, sample().copy(steeringAngleDeg = 450f), nav).isEmpty())
    }
    @Test fun lowConfidenceFutureStaleAndTestFramesAreRejected() {
        for (o in listOf(evidence().copy(confidence = 0.8), evidence().copy(confidence = Double.NaN),
            evidence(3_000), evidence(-1_000), evidence().copy(origin = ObservationOrigin.TEST))) {
            assertTrue(PerceptionMonitor().tick(o, sample(), nav).isEmpty())
        }
    }
    @Test fun unchangedConditionDoesNotRepeatButNewOccurrenceDoes() {
        val m = PerceptionMonitor()
        assertEquals(2, m.tick(evidence(), sample(), nav).size)
        assertTrue(m.tick(evidence(2_000), sample(2_000), nav).isEmpty())
        assertTrue(m.tick(evidence(3_000).copy(completedLaneChange = null, collisionWarning = false), sample(3_000), nav).isEmpty())
        assertEquals(2, m.tick(evidence(4_000), sample(4_000), nav).size)
    }
    @Test fun parkedOrUnknownSpeedDoesNotClaimMovingLaneChange() {
        for (s in listOf(sample().copy(speedKmh = null), sample().copy(gear = Gear.PARK), sample().copy(speedKmh = 0f)))
            assertTrue(PerceptionMonitor().tick(evidence(), s, nav).isEmpty())
    }
    @Test fun reverseCanWarnCollisionButNotDirectionalLaneChange() {
        assertEquals(listOf(PerceptionAlertKind.COLLISION), PerceptionMonitor().tick(evidence(), sample().copy(gear = Gear.REVERSE), nav).map { it.kind })
    }
    @Test fun noGuidanceCannotClaimOppositeLaneMovement() {
        assertEquals(listOf(PerceptionAlertKind.COLLISION), PerceptionMonitor().tick(evidence(), sample(), NavState()).map { it.kind })
    }
    @Test fun controlsRequireFreshParkAndStoppedSpeedAndPlatformPermission() {
        val parked = sample().copy(gear = Gear.PARK, speedKmh = 0f)
        assertFalse(DrivingPolicy.restricted(false, parked))
        for (s in listOf(sample(), parked.copy(speedKmh = null), parked.copy(gear = Gear.UNKNOWN), parked.copy(speedKmh = 2f)))
            assertTrue(DrivingPolicy.restricted(false, s))
        assertTrue(DrivingPolicy.restricted(true, parked))
        assertTrue(DrivingPolicy.restricted(false, parked, 20_000))
    }
}
