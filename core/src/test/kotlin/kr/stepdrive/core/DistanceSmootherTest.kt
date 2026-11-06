package kr.stepdrive.core

import kr.stepdrive.core.coach.DistanceSmoother
import kr.stepdrive.core.model.Gear
import kr.stepdrive.core.model.Maneuver
import kr.stepdrive.core.model.ManeuverType
import kr.stepdrive.core.model.NavState
import kr.stepdrive.core.model.NavStatus
import kr.stepdrive.core.model.VehicleSample
import kotlin.test.Test
import kotlin.test.assertEquals

class DistanceSmootherTest {
    @Test fun rerouteWithIdenticalFirstInstructionResetsOldDistanceEstimate() {
        val s = DistanceSmoother()
        s.apply(nav(300.0), VehicleSample(0, 36f, Gear.DRIVE))
        assertEquals(280.0, s.apply(nav(300.0), VehicleSample(2_000, 36f, Gear.DRIVE)).next!!.distanceM)
        assertEquals(300.0, s.apply(nav(300.0).copy(routeRevision = 1), VehicleSample(3_000, 36f, Gear.DRIVE)).next!!.distanceM)
    }
    private fun nav(d: Double, remain: Int = 5000) = NavState(NavStatus.ROUTE_DRIVE, listOf(Maneuver(ManeuverType.RIGHT, d, "우회전")), remainDistanceM = remain)

    @Test
    fun `distance keeps shrinking with speed while the navigation value is unchanged`() {
        val s = DistanceSmoother()
        val stale = nav(300.0)
        assertEquals(300.0, s.apply(stale, VehicleSample(0, 36f, Gear.DRIVE)).next!!.distanceM)       // first sight: resync
        val after1s = s.apply(stale, VehicleSample(1000, 36f, Gear.DRIVE))                               // 10 m/s
        assertEquals(290.0, after1s.next!!.distanceM, 0.01)
        assertEquals(4990, after1s.remainDistanceM)
        val after3s = s.apply(stale, VehicleSample(3000, 36f, Gear.DRIVE))
        assertEquals(270.0, after3s.next!!.distanceM, 0.01)
    }

    @Test
    fun `a new navigation value resets the estimate`() {
        val s = DistanceSmoother()
        s.apply(nav(300.0), VehicleSample(0, 36f, Gear.DRIVE))
        s.apply(nav(300.0), VehicleSample(2000, 36f, Gear.DRIVE))
        val fresh = s.apply(nav(250.0), VehicleSample(3000, 36f, Gear.DRIVE))
        assertEquals(250.0, fresh.next!!.distanceM)
    }

    @Test
    fun `standing still changes nothing and distance never goes negative`() {
        val s = DistanceSmoother()
        s.apply(nav(20.0), VehicleSample(0, 0f, Gear.DRIVE))
        assertEquals(20.0, s.apply(nav(20.0), VehicleSample(1000, 0f, Gear.DRIVE)).next!!.distanceM)
        s.apply(nav(20.0), VehicleSample(2000, 72f, Gear.DRIVE))
        assertEquals(0.0, s.apply(nav(20.0), VehicleSample(5000, 72f, Gear.DRIVE)).next!!.distanceM)
    }
}
