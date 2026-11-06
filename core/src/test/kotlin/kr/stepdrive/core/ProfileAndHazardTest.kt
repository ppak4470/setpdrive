package kr.stepdrive.core

import kr.stepdrive.core.demo.DemoScenario
import kr.stepdrive.core.hazard.Geo
import kr.stepdrive.core.hazard.HazardIndex
import kr.stepdrive.core.model.LatLng
import kr.stepdrive.core.profile.DriverProfile
import kr.stepdrive.core.profile.PinHasher
import kr.stepdrive.core.profile.ProfilePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileAndHazardTest {

    @Test
    fun `pin is hashed with a salt and verified in constant time`() {
        val salt = PinHasher.newSalt()
        val hash = PinHasher.hash("1234", salt)
        assertTrue(PinHasher.verify("1234", salt, hash))
        assertFalse(PinHasher.verify("1235", salt, hash))
        assertTrue(hash != PinHasher.hash("1234", PinHasher.newSalt()))
    }

    @Test
    fun `idle profiles expire for used-car hand-over`() {
        val day = 24L * 60 * 60 * 1000
        val p = DriverProfile("p1", "민수", "h", "s", createdAtMs = 0, lastUsedAtMs = 0)
        assertFalse(ProfilePolicy.isExpired(p, nowMs = 100 * day))
        assertTrue(ProfilePolicy.isExpired(p, nowMs = 181 * day))
    }

    @Test
    fun `hazard index finds zones on a route and ahead of the car`() {
        val idx = HazardIndex(DemoScenario.hazards)
        val fast = DemoScenario.courseCandidates.first { it.id == "fast" }
        assertEquals(listOf("H-1", "H-2"), idx.onRoute(fast.polyline).map { it.id })
        val at = LatLng(37.5436, 127.0548)
        val ahead = assertNotNull(idx.nearestAhead(at, headingDeg = 50.0))
        assertEquals("H-1", ahead.hazard.id)
        assertTrue(ahead.distanceM in 50.0..150.0)
        assertNull(idx.nearestAhead(at, headingDeg = 230.0)) // same spot, driving away
        assertTrue(Geo.distanceM(LatLng(37.5, 127.0), LatLng(37.5, 127.01)) in 870.0..900.0)
    }
}
