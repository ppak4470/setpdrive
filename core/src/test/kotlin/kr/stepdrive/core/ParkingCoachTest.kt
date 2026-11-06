package kr.stepdrive.core

import kr.stepdrive.core.model.Gear
import kr.stepdrive.core.model.VehicleSample
import kr.stepdrive.core.parking.ParkingCoach
import kr.stepdrive.core.parking.ParkingStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParkingCoachTest {
    private fun rev(t: Long, speed: Float, angle: Float?) = VehicleSample(t, speed, Gear.REVERSE, steeringAngleDeg = angle)

    @Test
    fun `checklist advances on movement, steering use, stop and park`() {
        val c = ParkingCoach()
        val first = c.tick(rev(0, 0f, 0f))
        assertEquals(ParkingStep.ALIGN, c.step)
        assertTrue(first.single().text.contains("확인"))
        c.tick(rev(1000, 2f, 0f))                     // started reversing → 진입 준비
        assertEquals(ParkingStep.TURN_REVERSE, c.step)
        c.tick(rev(2000, 3f, 400f))                   // wheel turned ...
        c.tick(rev(3000, 2f, 100f))                   // ... and released → 위치 확인
        assertEquals(ParkingStep.STRAIGHTEN, c.step)
        c.tick(rev(4000, 0f, 30f))
        val check = c.tick(rev(5600, 0f, 30f))        // stopped 1.6 s → 마무리 점검, steering as reference only
        assertEquals(ParkingStep.CHECK, c.step)
        assertTrue(check.any { it.text.contains("P에") })
        assertTrue(check.any { it.text.contains("참고") })
        c.tick(VehicleSample(7000, 0f, Gear.PARK, steeringAngleDeg = 30f))   // P → done
        assertTrue(c.isDone)
    }

    @Test
    fun `fast reversing is warned once per gap`() {
        val c = ParkingCoach()
        c.tick(rev(0, 0f, 0f)); c.tick(rev(1000, 2f, 0f))
        val warn = c.tick(rev(2000, 8f, 0f))
        assertTrue(warn.any { it.isWarning && it.text.contains("천천히") })
        val again = c.tick(rev(3000, 8f, 0f))
        assertTrue(again.none { it.isWarning })
    }

    @Test
    fun `voice commands move and repeat steps`() {
        val c = ParkingCoach()
        c.tick(rev(0, 0f, 0f))
        assertEquals(ParkingStep.TURN_REVERSE, c.next()!!.step)
        assertEquals(ParkingStep.TURN_REVERSE.spoken, c.repeat().text)
    }

    @Test
    fun `works without a steering angle signal`() {
        val c = ParkingCoach()
        c.tick(rev(0, 0f, null))
        c.tick(rev(1000, 2f, null))                   // moving → 진입 준비
        c.tick(rev(2000, 0f, null)); c.tick(rev(3600, 0f, null))   // stopped → 위치 확인
        assertEquals(ParkingStep.STRAIGHTEN, c.step)
        c.tick(rev(5200, 0f, null)); c.tick(rev(6800, 0f, null))   // still stopped → 마무리 점검
        assertEquals(ParkingStep.CHECK, c.step)
        c.tick(VehicleSample(6000, 0f, Gear.PARK))
        assertTrue(c.isDone)
    }
}
