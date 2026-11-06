package kr.stepdrive.core

import kr.stepdrive.core.course.CourseRecommender
import kr.stepdrive.core.demo.DemoScenario
import kr.stepdrive.core.hazard.HazardIndex
import kr.stepdrive.core.model.Tier
import kr.stepdrive.core.model.TripScore
import kr.stepdrive.core.score.SkillState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CourseAndSkillTest {

    @Test
    fun `city driver gets the hazard-free course with an explanation`() {
        val rec = CourseRecommender(HazardIndex(DemoScenario.hazards))
        val result = rec.evaluate(DemoScenario.courseCandidates, Tier.CITY)
        val best = result.single { it.recommended }
        assertEquals("safe", best.candidate.id)
        assertEquals(0, best.hazardsOnRoute.size)
        assertEquals(2, result.first { it.candidate.id == "fast" }.hazardsOnRoute.size)
        assertTrue(best.reason.contains("4분 더 걸리지만"))
        assertTrue(best.reason.contains("2곳"))
        assertEquals(Tier.HIGHWAY, result.first { it.candidate.id == "highway" }.tier)
    }

    private fun trip(score: Int, tier: Tier, signal: Double = 1.0) = TripScore(
        "x", 0, 1, 5.0, tier, score, (score * tier.xpWeight).toInt(), signal, 0.0, 0.0, 0.0, null, emptyList(), "", null,
    )

    @Test
    fun `tiers unlock by accumulated xp and recent quality, not by repetition alone`() {
        var s = SkillState()
        assertEquals(Tier.CITY, s.unlockedTier())
        repeat(3) { s = s.apply(trip(100, Tier.CITY)) }
        assertEquals(300, s.xp)
        assertEquals(Tier.NATIONAL_ROAD, s.unlockedTier())
        // Lots of xp but poor recent signal habit keeps the highway closed.
        var sloppy = SkillState(xp = 2_000)
        repeat(5) { sloppy = sloppy.apply(trip(90, Tier.CITY, signal = 0.3)) }
        assertEquals(Tier.CITY, sloppy.unlockedTier())
        assertFalse(sloppy.graduated())
    }

    @Test
    fun `graduation needs highway trips and a high recent average`() {
        var s = SkillState(xp = 900)
        repeat(5) { s = s.apply(trip(90, Tier.HIGHWAY)) }
        assertTrue(s.graduated())
        assertEquals(null, s.xpToNextTier())
    }
}
