package kr.stepdrive.core

import kotlinx.serialization.json.Json
import kr.stepdrive.core.coach.CoachEngine
import kr.stepdrive.core.coach.Priority
import kr.stepdrive.core.hazard.HazardIndex
import kr.stepdrive.core.model.*
import kr.stepdrive.core.parking.ParkingCoach
import kr.stepdrive.core.parking.ParkingStep
import kr.stepdrive.core.practice.PracticeProgress
import kr.stepdrive.core.profile.DriverProfile
import kr.stepdrive.core.score.ScoreEngine
import kotlin.test.*

class RegressionTest {
    private val hazards = HazardIndex(emptyList())
    private val place = LatLng(37.5, 127.0)
    private fun nav(distance: Double) = NavState(NavStatus.ROUTE_DRIVE, listOf(Maneuver(ManeuverType.RIGHT, distance, "우회전")), location = place)
    private fun engine() = ScoreEngine("trip", Tier.CITY, hazards)

    @Test fun missingSignalIsNotScoredAsOff() {
        val e = engine()
        e.onTick(VehicleSample(1_000, 30f, Gear.DRIVE), nav(100.0))
        e.onTick(VehicleSample(2_000, 30f, Gear.DRIVE), nav(10.0))
        val trip = e.finish(3_000)
        assertNull(trip.signalRate)
        assertTrue(trip.events.none { it.kind == TripEvent.Kind.SIGNAL_MISSED })
        assertTrue(trip.provisional)
    }

    @Test fun aSignalFailureDuringTheTurnMakesItUnevaluable() {
        val e = engine()
        e.onTick(VehicleSample(1_000, 30f, Gear.DRIVE, TurnSignal.OFF), nav(100.0))
        e.onTick(VehicleSample(2_000, 30f, Gear.DRIVE, TurnSignal.UNKNOWN), nav(10.0))
        assertNull(e.finish(3_000).signalRate)
    }

    @Test fun observationsStartingAfterTheLeadDistanceAreNotPenalized() {
        val e = engine()
        e.onTick(VehicleSample(1_000, 30f, Gear.DRIVE, TurnSignal.OFF), nav(20.0))
        assertNull(e.finish(2_000).signalRate)
    }

    @Test fun emptyTurnListFinishesAManeuverOnlyOnce() {
        val e = engine()
        e.onTick(VehicleSample(1_000, 30f, Gear.DRIVE, TurnSignal.RIGHT), nav(100.0))
        e.onTick(VehicleSample(2_000, 30f, Gear.DRIVE, TurnSignal.RIGHT), nav(10.0))
        repeat(4) { i ->
            e.onTick(VehicleSample(3_000L + i * 1_000, 30f, Gear.DRIVE, TurnSignal.OFF), NavState(NavStatus.ROUTE_DRIVE))
        }
        val trip = e.finish(8_000)
        assertEquals(1, trip.events.count { it.kind == TripEvent.Kind.SIGNAL_OK })
        assertEquals(1.0, trip.signalRate)
        assertEquals(1, e.finish(9_000).events.count { it.kind == TripEvent.Kind.SIGNAL_OK })
    }

    @Test fun cancelledGuidanceDoesNotGradeAnUnfinishedTurn() {
        val e = engine()
        e.onTick(VehicleSample(1_000, 30f, Gear.DRIVE, TurnSignal.OFF), nav(100.0))
        e.onTick(VehicleSample(2_000, 30f, Gear.DRIVE, TurnSignal.OFF), NavState(NavStatus.FREE_DRIVE))
        assertNull(e.finish(3_000).signalRate)
    }

    @Test fun steeringEventsDoNotShortenTheSpeedMeasurementInterval() {
        val e = engine()
        val coach = CoachEngine(hazards)
        val free = NavState(NavStatus.FREE_DRIVE)
        val before = VehicleSample(1_000, 50f, Gear.DRIVE, speedTMs = 1_000)
        val steering = before.copy(tMs = 1_990, steeringAngleDeg = 10f)
        val after = VehicleSample(2_000, 49f, Gear.DRIVE, speedTMs = 2_000)
        listOf(before, steering, after).forEach { e.onTick(it, free) }
        coach.tick(before, free)
        coach.tick(steering, free)
        assertTrue(coach.tick(after, free).isEmpty())
        assertTrue(e.finish(3_000).events.none { it.kind == TripEvent.Kind.HARSH_BRAKE || it.kind == TripEvent.Kind.HARSH_ACCEL })
    }

    @Test fun turnAnnouncementSurvivesAHigherPriorityWarning() {
        val coach = CoachEngine(hazards)
        val turn = nav(150.0).copy(accidentProneAheadM = 100)
        assertEquals(Priority.WARNING, coach.tick(VehicleSample(1_000, 30f, Gear.DRIVE), turn).single().priority)
        assertEquals(Priority.TURN, coach.tick(VehicleSample(4_100, 30f, Gear.DRIVE), turn.copy(accidentProneAheadM = null)).single().priority)
    }

    @Test fun consecutiveTurnsWithTheSameDescriptionAreBothAnnounced() {
        val coach = CoachEngine(hazards)
        assertEquals(Priority.TURN, coach.tick(VehicleSample(1_000, 30f, Gear.DRIVE), nav(150.0)).single().priority)
        coach.tick(VehicleSample(2_000, 30f, Gear.DRIVE), nav(10.0))
        assertEquals(Priority.TURN, coach.tick(VehicleSample(5_000, 30f, Gear.DRIVE), nav(150.0)).single().priority)
    }

    @Test fun voiceCannotCompleteParkingBeforeParkIsSelected() {
        val parking = ParkingCoach()
        repeat(3) { parking.next() }
        assertEquals(ParkingStep.CHECK, parking.step)
        assertNull(parking.next())
        val done = parking.tick(VehicleSample(1_000, 0f, Gear.PARK))
        assertEquals(ParkingStep.DONE, done.single().step)
    }

    @Test fun completedPracticeIsClearedAfterAQualifiedPassWithoutAnEvent() {
        val point = PracticePoint("practice", TripEvent.Kind.HARSH_BRAKE, "부드럽게 멈추기", place, "", "", 0)
        val progress = PracticeProgress(point)
        progress.onTick(VehicleSample(1_000, 30f, Gear.DRIVE), nav(100.0))
        progress.onTick(VehicleSample(2_000, 30f, Gear.DRIVE), nav(90.0))
        val trip = trip().let { it.copy(reviewedPractice = progress.review(it)) }
        assertEquals(true, trip.reviewedPractice?.verified)
        assertNull(profile(point).withTrip(trip, 3_000).openPractice)
    }

    @Test fun missingSignalAndUnvisitedPracticeRemainPending() {
        val point = PracticePoint("practice", TripEvent.Kind.SIGNAL_MISSED, "깜빡이", place, "", "", 0)
        val progress = PracticeProgress(point)
        progress.onTick(VehicleSample(1_000, 30f, Gear.DRIVE), nav(100.0))
        val reviewed = progress.review(trip())
        assertNull(reviewed?.verified)
        assertNotNull(profile(point).withTrip(trip().copy(reviewedPractice = reviewed), 3_000).openPractice)
        assertNull(PracticeProgress(point).review(trip())?.verified)
    }

    @Test fun oldProfileJsonLoadsWithNewOptionalFields() {
        val old = """{"id":"p","name":"운전자","pinHash":"hash","salt":"salt","createdAtMs":1,"lastUsedAtMs":2}"""
        assertEquals("p", Json.decodeFromString<DriverProfile>(old).id)
    }

    private fun profile(point: PracticePoint) = DriverProfile("p", "운전자", "hash", "salt", 0, 0, openPractice = point)
    private fun trip() = TripScore("t", 1_000, 2_000, 5.0, Tier.CITY, 100, 100, null, 0.0, 0.0, 0.0, null, emptyList(), "", null)
}
