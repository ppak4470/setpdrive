package kr.stepdrive.core

import kr.stepdrive.core.hazard.HazardIndex
import kr.stepdrive.core.model.Gear
import kr.stepdrive.core.model.LatLng
import kr.stepdrive.core.model.Maneuver
import kr.stepdrive.core.model.ManeuverType
import kr.stepdrive.core.model.NavState
import kr.stepdrive.core.model.NavStatus
import kr.stepdrive.core.model.Tier
import kr.stepdrive.core.model.TripEvent
import kr.stepdrive.core.model.TurnSignal
import kr.stepdrive.core.model.VehicleSample
import kr.stepdrive.core.score.ScoreEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScoreEngineTest {
    private val noHazards = HazardIndex(emptyList())

    private fun drive(engine: ScoreEngine, signalOn: Boolean) {
        // Approach a right turn from 300 m at 36 km/h (10 m/s); signal from 150 m if requested.
        var t = 0L
        var d = 300.0
        var odo = 100f
        while (d > 0) {
            val sig = if (signalOn && d <= 150) TurnSignal.RIGHT else TurnSignal.OFF
            engine.onTick(
                VehicleSample(t, 36f, Gear.DRIVE, sig, odometerKm = odo),
                NavState(NavStatus.ROUTE_DRIVE, listOf(Maneuver(ManeuverType.RIGHT, d, "우회전")), location = LatLng(37.5, 127.0)),
            )
            t += 1000; d -= 10; odo += 0.01f
        }
        // Next maneuver far away → the right turn is finished.
        engine.onTick(VehicleSample(t, 36f, Gear.DRIVE, odometerKm = odo), NavState(NavStatus.ROUTE_DRIVE, listOf(Maneuver(ManeuverType.LEFT, 900.0, "좌회전"))))
    }

    @Test
    fun `signal used before the turn counts as success`() {
        val e = ScoreEngine("t1", Tier.CITY, noHazards)
        drive(e, signalOn = true)
        val score = e.finish(60_000)
        assertEquals(1.0, score.signalRate)
        assertTrue(score.events.any { it.kind == TripEvent.Kind.SIGNAL_OK })
        assertEquals(100, score.rawScore)
    }

    @Test
    fun `missing signal lowers the score and creates a practice point`() {
        val e = ScoreEngine("t2", Tier.CITY, noHazards)
        drive(e, signalOn = false)
        val score = e.finish(60_000)
        assertEquals(0.0, score.signalRate)
        assertEquals(70, score.rawScore)
        val p = assertNotNull(score.nextPractice)
        assertEquals(TripEvent.Kind.SIGNAL_MISSED, p.kind)
        assertTrue(p.criterion.contains("30m"))
    }

    @Test
    fun `harsh braking is detected from speed samples`() {
        val e = ScoreEngine("t3", Tier.CITY, noHazards)
        val nav = NavState(NavStatus.FREE_DRIVE)
        e.onTick(VehicleSample(0, 50f, Gear.DRIVE, odometerKm = 10f), nav)
        e.onTick(VehicleSample(1000, 30f, Gear.DRIVE, odometerKm = 10.01f), nav) // -5.6 m/s²
        e.onTick(VehicleSample(2000, 30f, Gear.DRIVE, odometerKm = 10.02f), nav)
        val score = e.finish(3000)
        assertEquals(1, score.events.count { it.kind == TripEvent.Kind.HARSH_BRAKE })
        assertTrue(score.rawScore < 100)
    }

    @Test
    fun `xp is weighted by tier`() {
        val city = ScoreEngine("a", Tier.CITY, noHazards).also { drive(it, true) }.finish(1)
        val hwy = ScoreEngine("b", Tier.HIGHWAY, noHazards).also { drive(it, true) }.finish(1)
        assertEquals(100, city.xp)
        assertEquals(160, hwy.xp)
    }
}
