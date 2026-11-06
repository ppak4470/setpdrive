package kr.stepdrive.core

import kr.stepdrive.core.coach.CoachEngine
import kr.stepdrive.core.coach.OperationWatch
import kr.stepdrive.core.coach.Priority
import kr.stepdrive.core.hazard.HazardIndex
import kr.stepdrive.core.model.Gear
import kr.stepdrive.core.model.Maneuver
import kr.stepdrive.core.model.ManeuverType
import kr.stepdrive.core.model.NavState
import kr.stepdrive.core.model.NavStatus
import kr.stepdrive.core.model.Tier
import kr.stepdrive.core.model.TripScore
import kr.stepdrive.core.model.TurnSignal
import kr.stepdrive.core.model.VehicleSample
import kr.stepdrive.core.report.TripSummary
import kr.stepdrive.core.score.SkillState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedbackAndNoticeTest {
    private fun trip(score: Int, provisional: Boolean = false) = TripScore(
        "x", 0, 1, 5.0, Tier.CITY, score, if (provisional) 0 else score, 1.0, 0.0, 0.0, 0.0, null, emptyList(), "", null, provisional,
    )

    @Test
    fun `two 90+ trips in a row quiet the coach by one level, a weak trip restores it`() {
        var s = SkillState()
        assertEquals(3, s.feedbackLevel)
        s = s.apply(trip(92)); assertEquals(3, s.feedbackLevel)
        s = s.apply(trip(94)); assertEquals(2, s.feedbackLevel)
        s = s.apply(trip(95)); s = s.apply(trip(91)); assertEquals(1, s.feedbackLevel)
        s = s.apply(trip(78)); assertEquals(2, s.feedbackLevel)
    }

    @Test
    fun `provisional trips are shown but not counted`() {
        var s = SkillState()
        s = s.apply(trip(100, provisional = true))
        assertEquals(1, s.trips); assertEquals(0, s.xp); assertTrue(s.recentScores.isEmpty())
        assertTrue(TripSummary.sentence(trip(100, provisional = true)).contains("보류"))
    }

    @Test
    fun `level 1 keeps warnings and turn announcements only`() {
        val quiet = CoachEngine(HazardIndex(emptyList()), feedbackLevel = 1)
        val turn = { d: Double -> NavState(NavStatus.ROUTE_DRIVE, listOf(Maneuver(ManeuverType.RIGHT, d, "대왕판교IC 방면"))) }
        val prep = quiet.tick(VehicleSample(0, 40f, Gear.DRIVE), turn(250.0))
        assertEquals(Priority.TURN, prep.single().priority)
        assertTrue(prep.single().text.contains("대왕판교IC"))
        val confirmed = quiet.tick(VehicleSample(5000, 40f, Gear.DRIVE, TurnSignal.RIGHT), turn(120.0))
        assertTrue(confirmed.isEmpty())      // praise is muted at level 1
    }

    @Test
    fun `consecutive turns are announced together`() {
        val coach = CoachEngine(HazardIndex(emptyList()))
        val nav = NavState(NavStatus.ROUTE_DRIVE, listOf(Maneuver(ManeuverType.RIGHT, 250.0, "우회전"), Maneuver(ManeuverType.LEFT, 550.0, "좌회전")))
        val first = coach.tick(VehicleSample(0, 40f, Gear.DRIVE), nav)
        assertTrue(first.single().text.startsWith("곧 우회전"))
        val next = coach.tick(VehicleSample(3500, 40f, Gear.DRIVE), nav.copy(maneuvers = nav.maneuvers.map { it.copy(distanceM = it.distanceM - 40) }))
        assertTrue(next.any { it.text.contains("바로 좌회전") }, next.joinToString { it.text })
    }

    @Test
    fun `steering a lot at speed with the signal off gives one notice`() {
        val coach = CoachEngine(HazardIndex(emptyList()))
        val free = NavState(NavStatus.FREE_DRIVE)
        coach.tick(VehicleSample(0, 50f, Gear.DRIVE, steeringAngleDeg = 0f), free)
        val notice = coach.tick(VehicleSample(1000, 50f, Gear.DRIVE, TurnSignal.OFF, steeringAngleDeg = -120f), free)
        assertEquals(Priority.NOTICE, notice.single().priority)
        assertTrue(coach.tick(VehicleSample(2000, 50f, Gear.DRIVE, TurnSignal.OFF, steeringAngleDeg = -120f), free).isEmpty())
    }

    @Test
    fun `gear mistakes while parking are noticed once`() {
        val w = OperationWatch()
        assertNull(w.tick(VehicleSample(0, 3f, Gear.REVERSE, brakePressed = false), false))
        val moving = w.tick(VehicleSample(1000, 3f, Gear.DRIVE, brakePressed = false), false)
        assertEquals(OperationWatch.Kind.SHIFT_WHILE_MOVING, moving!!.kind)
        val noBrake = w.tick(VehicleSample(2000, 0f, Gear.REVERSE, brakePressed = false), false)
        assertEquals(OperationWatch.Kind.SHIFT_WITHOUT_BRAKE, noBrake!!.kind)
        w.tick(VehicleSample(3000, 0f, Gear.REVERSE, brakePressed = false), true)
        val noPark = w.tick(VehicleSample(8000, 0f, Gear.REVERSE, brakePressed = false), true)
        assertEquals(OperationWatch.Kind.NO_PARK_AFTER_STOP, noPark!!.kind)
    }
}
