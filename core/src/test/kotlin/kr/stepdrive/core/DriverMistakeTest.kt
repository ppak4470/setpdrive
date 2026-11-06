package kr.stepdrive.core

import kr.stepdrive.core.coach.CoachEngine
import kr.stepdrive.core.coach.OperationWatch
import kr.stepdrive.core.coach.Priority
import kr.stepdrive.core.hazard.HazardIndex
import kr.stepdrive.core.model.*
import kr.stepdrive.core.parking.ParkingCoach
import kr.stepdrive.core.parking.ParkingStep
import kr.stepdrive.core.score.ScoreEngine
import kotlin.test.*

/** Deliberate driver errors and missing evidence must not produce success or a false warning. */
class DriverMistakeTest {
    private val hazards = HazardIndex(emptyList())
    private fun nav(d: Double = 100.0, type: ManeuverType = ManeuverType.LEFT, name: String = "검증 회전") =
        NavState(NavStatus.ROUTE_DRIVE, listOf(Maneuver(type, d, name)))
    private fun sample(t: Long, signal: TurnSignal = TurnSignal.RIGHT) = VehicleSample(t, 30f, Gear.DRIVE, signal)
    private fun isMismatch(text: String) = text.startsWith("안내는") && text.contains("깜빡이는")
    private fun score(vararg observations: Pair<Double, TurnSignal>): TripScore {
        val e = ScoreEngine("mistake", Tier.CITY, hazards)
        observations.forEachIndexed { i, (d, signal) -> e.onTick(sample(1_000L + i * 1_000, signal), nav(d)) }
        return e.finish(20_000)
    }

    @Test fun oppositeIndicatorIsExplainedInBothDirections() {
        listOf(ManeuverType.LEFT to TurnSignal.RIGHT, ManeuverType.RIGHT to TurnSignal.LEFT).forEach { (type, wrong) ->
            val c = CoachEngine(hazards, minGapMs = 0)
            c.tick(sample(0, wrong), nav(type = type))
            val event = c.tick(sample(1_100, wrong), nav(90.0, type)).single()
            assertTrue(isMismatch(event.text), event.text)
            assertTrue(event.text.length <= 40)
        }
    }

    @Test fun mismatchRemainsEnabledAtEveryFeedbackLevel() {
        (1..3).forEach { level ->
            val c = CoachEngine(hazards, minGapMs = 0, feedbackLevel = level)
            c.tick(sample(0), nav())
            assertTrue(c.tick(sample(1_100), nav(90.0)).any { isMismatch(it.text) })
        }
    }

    @Test fun briefOppositeIndicatorDoesNotTriggerMismatch() {
        val c = CoachEngine(hazards, minGapMs = 0)
        c.tick(sample(0), nav())
        assertTrue(c.tick(sample(400, TurnSignal.LEFT), nav(95.0)).none { isMismatch(it.text) })
        assertTrue(c.tick(sample(2_000, TurnSignal.LEFT), nav(80.0)).none { isMismatch(it.text) })
    }

    @Test fun continuousMistakeWarnsOnceAndNewMistakeWarnsAgain() {
        val c = CoachEngine(hazards, minGapMs = 0)
        val events = mutableListOf<String>()
        listOf(0L, 1_100, 3_000).forEach { events += c.tick(sample(it), nav()).map { it.text } }
        assertEquals(1, events.count(::isMismatch))
        c.tick(sample(4_000, TurnSignal.LEFT), nav())
        c.tick(sample(5_000), nav())
        assertTrue(c.tick(sample(6_100), nav()).any { isMismatch(it.text) })
    }

    @Test fun correctedIndicatorCancelsAQueuedMismatch() {
        val c = CoachEngine(hazards)
        c.tick(sample(0), nav())
        c.tick(sample(1_100), nav(90.0))
        c.tick(sample(1_500, TurnSignal.LEFT), nav(85.0))
        assertTrue(c.tick(sample(4_000, TurnSignal.LEFT), nav(70.0)).none { isMismatch(it.text) })
    }

    @Test fun oppositeIndicatorCancelsAQueuedPraise() {
        val c = CoachEngine(hazards)
        c.tick(sample(0, TurnSignal.LEFT), nav())
        c.tick(sample(100), nav(99.0))
        val events = (1..8).flatMap { c.tick(sample(it * 1_000L), nav(99.0 - it)) }
        assertTrue(events.none { it.priority == Priority.PRAISE })
        assertTrue(events.any { isMismatch(it.text) })
    }

    @Test fun hazardWarningWinsButPersistentMismatchIsStillDelivered() {
        val c = CoachEngine(hazards)
        c.tick(sample(0), nav())
        assertEquals(Priority.WARNING, c.tick(sample(1_100), nav(90.0).copy(accidentProneAheadM = 100)).single().priority)
        assertTrue(c.tick(sample(4_200), nav(80.0)).any { isMismatch(it.text) })
    }

    @Test fun missingStaleStoppedReverseOrFarSignalsDoNotTriggerMismatch() {
        val cases = listOf(
            sample(20_000).copy(turnSignal = TurnSignal.UNKNOWN) to nav(),
            sample(20_000).copy(speedKmh = null) to nav(),
            sample(20_000).copy(speedKmh = 0f) to nav(),
            sample(20_000).copy(speedTMs = 0) to nav(),
            sample(20_000).copy(gear = Gear.REVERSE) to nav(),
            sample(20_000) to nav(2_000.0),
            sample(20_000) to nav().copy(status = NavStatus.FREE_DRIVE),
            sample(20_000) to nav().copy(guidanceTMs = 0),
        )
        cases.forEach { (s, n) ->
            val c = CoachEngine(hazards, minGapMs = 0)
            c.tick(s, n)
            val later = s.copy(tMs = 21_100, speedTMs = if (s.speedTMs == 0L) 0 else 21_100)
            assertTrue(c.tick(later, n).none { isMismatch(it.text) }, "$s / $n")
        }
    }

    @Test fun cancelledGuidanceCancelsPendingMismatch() {
        val c = CoachEngine(hazards)
        c.tick(sample(0), nav())
        c.tick(sample(1_100), nav())
        assertTrue(c.tick(sample(4_000), NavState(NavStatus.FREE_DRIVE)).none { isMismatch(it.text) })
    }

    @Test fun correctThenOppositeIsNotScoredAsSuccess() {
        val trip = score(100.0 to TurnSignal.LEFT, 50.0 to TurnSignal.RIGHT, 10.0 to TurnSignal.RIGHT)
        assertEquals(0.0, trip.signalRate)
        assertTrue(trip.events.none { it.kind == TripEvent.Kind.SIGNAL_OK })
    }

    @Test fun correctThenSwitchedOffBeforeTheLeadDistanceIsNotSuccess() {
        assertEquals(0.0, score(100.0 to TurnSignal.LEFT, 60.0 to TurnSignal.OFF, 10.0 to TurnSignal.OFF).signalRate)
    }

    @Test fun correctingTheIndicatorEarlyCanRestoreSuccess() {
        assertEquals(1.0, score(100.0 to TurnSignal.RIGHT, 60.0 to TurnSignal.LEFT, 10.0 to TurnSignal.LEFT).signalRate)
    }

    @Test fun lateCorrectionDoesNotRestoreAnEarlierInvalidatedSuccess() {
        assertEquals(0.0, score(100.0 to TurnSignal.LEFT, 50.0 to TurnSignal.RIGHT, 10.0 to TurnSignal.LEFT).signalRate)
    }

    @Test fun oppositeIndicatorCloseToTheTurnInvalidatesSuccess() {
        assertEquals(0.0, score(100.0 to TurnSignal.LEFT, 20.0 to TurnSignal.RIGHT, 10.0 to TurnSignal.RIGHT).signalRate)
    }

    @Test fun unknownIndicatorAfterAConfirmedSignalLeavesTheTurnUnevaluated() {
        assertNull(score(100.0 to TurnSignal.LEFT, 10.0 to TurnSignal.UNKNOWN).signalRate)
    }

    @Test fun routeChangingBeforeTheTurnDoesNotCountItAsCompleted() {
        val e = ScoreEngine("reroute", Tier.CITY, hazards)
        e.onTick(sample(1_000, TurnSignal.OFF), nav(300.0))
        e.onTick(sample(2_000, TurnSignal.OFF), nav(500.0, ManeuverType.RIGHT, "새 경로"))
        assertNull(e.finish(3_000).signalRate)
    }

    @Test fun genericExitDoesNotInventARightIndicatorRequirement() {
        assertNull(ManeuverType.EXIT.requiredSignal)
    }

    @Test fun parkedOrMissingSpeedDoesNotEarnSignalSuccess() {
        listOf(Gear.PARK to 0f, Gear.DRIVE to null).forEach { (gear, speed) ->
            val e = ScoreEngine("unobserved", Tier.CITY, hazards)
            e.onTick(sample(1_000, TurnSignal.LEFT).copy(gear = gear, speedKmh = speed), nav(100.0))
            e.onTick(sample(2_000, TurnSignal.LEFT).copy(gear = gear, speedKmh = speed), nav(10.0))
            assertNull(e.finish(3_000).signalRate)
        }
    }

    @Test fun unknownSpeedMustNotPromptParkAsIfStopped() {
        val w = OperationWatch()
        w.tick(VehicleSample(0, null, Gear.REVERSE, brakePressed = false), true)
        assertNull(w.tick(VehicleSample(5_000, null, Gear.REVERSE, brakePressed = false), true))
    }

    @Test fun staleSpeedMustNotPromptParkAsIfStopped() {
        val w = OperationWatch()
        w.tick(VehicleSample(4_000, 0f, Gear.REVERSE, brakePressed = false, speedTMs = 0), true)
        assertNull(w.tick(VehicleSample(9_000, 0f, Gear.REVERSE, brakePressed = false, speedTMs = 0), true))
    }

    @Test fun reverseOverspeedIsWarnedOnTheFirstMovingSample() {
        assertTrue(ParkingCoach().tick(VehicleSample(0, 8f, Gear.REVERSE)).any { it.isWarning })
    }

    @Test fun reverseOverspeedIsStillWarnedDuringFinalCheck() {
        val p = ParkingCoach()
        repeat(3) { p.next() }
        assertEquals(ParkingStep.CHECK, p.step)
        assertTrue(p.tick(VehicleSample(0, 8f, Gear.REVERSE)).any { it.isWarning })
    }

    @Test fun missingSteeringDoesNotMeanTheWheelWasStraightened() {
        val p = ParkingCoach()
        p.tick(VehicleSample(0, 2f, Gear.REVERSE, steeringAngleDeg = 0f))
        p.tick(VehicleSample(1_000, 2f, Gear.REVERSE, steeringAngleDeg = 400f))
        p.tick(VehicleSample(2_000, 2f, Gear.REVERSE, steeringAngleDeg = null))
        assertEquals(ParkingStep.TURN_REVERSE, p.step)
    }

    @Test fun movingParkAndUnknownSpeedCannotCompleteParking() {
        val p = ParkingCoach()
        repeat(3) { p.next() }
        p.tick(VehicleSample(0, 5f, Gear.PARK))
        assertFalse(p.isDone)
        p.tick(VehicleSample(1_000, null, Gear.PARK))
        assertFalse(p.isDone)
        p.tick(VehicleSample(2_000, 0f, Gear.PARK))
        assertTrue(p.isDone)
    }

    @Test fun brakeAndGearEvidenceAreRequiredForGearWarnings() {
        val w = OperationWatch()
        w.tick(VehicleSample(0, 0f, Gear.PARK), false)
        assertNull(w.tick(VehicleSample(1_000, 0f, Gear.DRIVE, brakePressed = null), false))
        assertNull(w.tick(VehicleSample(2_000, 0f, Gear.REVERSE, brakePressed = true), false))
        assertEquals(OperationWatch.Kind.SHIFT_WHILE_MOVING,
            w.tick(VehicleSample(3_000, 4f, Gear.DRIVE, brakePressed = true), false)?.kind)
    }

    @Test fun parkingAtTheEndDoesNotEraseTheObservedTurnResult() {
        val e = ScoreEngine("park-end", Tier.CITY, hazards)
        e.onTick(sample(1_000, TurnSignal.LEFT), nav(100.0))
        e.onTick(sample(2_000, TurnSignal.LEFT), nav(10.0))
        e.onTick(VehicleSample(3_000, 0f, Gear.PARK, TurnSignal.OFF), nav(10.0))
        assertEquals(1.0, e.finish(4_000).signalRate)
    }

    @Test fun aSeparateMovingShiftCanWarnAgainAfterCooldown() {
        val w = OperationWatch()
        w.tick(VehicleSample(0, 4f, Gear.DRIVE), false)
        assertNotNull(w.tick(VehicleSample(1_000, 4f, Gear.REVERSE), false))
        assertNull(w.tick(VehicleSample(2_000, 4f, Gear.DRIVE), false))
        assertEquals(OperationWatch.Kind.SHIFT_WHILE_MOVING,
            w.tick(VehicleSample(6_000, 4f, Gear.REVERSE), false)?.kind)
    }

    @Test fun steeringReferenceDoesNotInferDirectionFromAnUnverifiedSign() {
        listOf(-120f, 120f).forEach { angle ->
            val p = ParkingCoach()
            repeat(2) { p.next() }
            p.tick(VehicleSample(1_000, 0f, Gear.REVERSE, steeringAngleDeg = angle))
            val reference = p.tick(VehicleSample(3_000, 0f, Gear.REVERSE, steeringAngleDeg = angle))
                .single { it.text.contains("참고") }.text
            assertTrue(reference.contains("120도"))
            assertFalse(reference.contains("왼쪽") || reference.contains("오른쪽"))
        }
    }

    @Test fun missingSpeedResetsTheStopHoldBeforeAParkReminder() {
        val w = OperationWatch()
        w.tick(VehicleSample(0, 0f, Gear.REVERSE, brakePressed = false), true)
        w.tick(VehicleSample(3_000, null, Gear.REVERSE, brakePressed = false), true)
        assertNull(w.tick(VehicleSample(4_000, 0f, Gear.REVERSE, brakePressed = false), true))
        assertNull(w.tick(VehicleSample(7_000, 0f, Gear.REVERSE, brakePressed = false), true))
        assertEquals(OperationWatch.Kind.NO_PARK_AFTER_STOP,
            w.tick(VehicleSample(8_100, 0f, Gear.REVERSE, brakePressed = false), true)?.kind)
    }
}
