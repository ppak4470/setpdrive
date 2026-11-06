package kr.stepdrive.core

import kr.stepdrive.core.coach.CoachEngine
import kr.stepdrive.core.coach.Priority
import kr.stepdrive.core.demo.DemoScenario
import kr.stepdrive.core.hazard.HazardIndex
import kr.stepdrive.core.model.Gear
import kr.stepdrive.core.model.LatLng
import kr.stepdrive.core.model.Maneuver
import kr.stepdrive.core.model.ManeuverType
import kr.stepdrive.core.model.NavState
import kr.stepdrive.core.model.NavStatus
import kr.stepdrive.core.model.TurnSignal
import kr.stepdrive.core.model.VehicleSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoachEngineTest {

    @Test
    fun `announce, confirm signal, then praise once`() {
        val coach = CoachEngine(HazardIndex(emptyList()))
        val turn = { d: Double -> NavState(NavStatus.ROUTE_DRIVE, listOf(Maneuver(ManeuverType.RIGHT, d, "우회전"))) }
        val far = coach.tick(VehicleSample(0, 40f, Gear.DRIVE), turn(900.0))
        assertTrue(far.isEmpty())
        val prep = coach.tick(VehicleSample(1000, 40f, Gear.DRIVE), turn(250.0))
        assertEquals(Priority.TURN, prep.single().priority)
        val confirmed = coach.tick(VehicleSample(5000, 40f, Gear.DRIVE, TurnSignal.RIGHT), turn(120.0))
        assertEquals("좋아요, 깜빡이 확인했어요", confirmed.single().text)
        val again = coach.tick(VehicleSample(6000, 40f, Gear.DRIVE, TurnSignal.RIGHT), turn(80.0))
        assertTrue(again.isEmpty())
    }

    @Test
    fun `missing signal at the turn gives one coaching line`() {
        val coach = CoachEngine(HazardIndex(emptyList()))
        val turn = { d: Double -> NavState(NavStatus.ROUTE_DRIVE, listOf(Maneuver(ManeuverType.LEFT, d, "좌회전"))) }
        coach.tick(VehicleSample(0, 30f, Gear.DRIVE, TurnSignal.OFF), turn(200.0))
        val atTurn = coach.tick(VehicleSample(10_000, 20f, Gear.DRIVE, TurnSignal.OFF), turn(20.0))
        assertEquals(Priority.COACH, atTurn.single().priority)
    }

    @Test
    fun `hazard warning fires once and before anything else`() {
        val coach = CoachEngine(HazardIndex(DemoScenario.hazards))
        val near = LatLng(37.5436, 127.0548) // ~130 m south-west of 성수사거리
        val nav = NavState(NavStatus.ROUTE_DRIVE, listOf(Maneuver(ManeuverType.RIGHT, 100.0, "우회전")), location = near, headingDeg = 50.0)
        val events = coach.tick(VehicleSample(0, 40f, Gear.DRIVE), nav)
        assertEquals(Priority.WARNING, events.first().priority)
        assertTrue(events.first().text.contains("사고가 잦은"))
        val second = coach.tick(VehicleSample(1000, 40f, Gear.DRIVE), nav)
        assertTrue(second.none { it.priority == Priority.WARNING })
    }

    @Test
    fun `utterances while driving stay short`() {
        val coach = CoachEngine(HazardIndex(DemoScenario.hazards))
        val all = DemoScenario.frames().flatMap { coach.tick(it.sample, it.nav) }
        assertTrue(all.isNotEmpty())
        all.forEach { assertTrue(it.text.length <= CoachEngine.MAX_CHARS_WHILE_DRIVING, it.text) }
    }
}
