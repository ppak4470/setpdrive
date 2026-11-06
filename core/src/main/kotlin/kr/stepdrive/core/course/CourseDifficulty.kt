package kr.stepdrive.core.course

import kr.stepdrive.core.model.RouteFeatures
import kr.stepdrive.core.model.Tier

/**
 * Turns route features into a 0..100 difficulty score and a tier.
 * The weights are a starting point and are meant to be tuned with test drives.
 */
object CourseDifficulty {
    fun score(f: RouteFeatures, hazardCount: Int): Int {
        var s = 0.0
        s += (f.intersections * 3).coerceAtMost(30)
        s += (f.merges * 10).coerceAtMost(30)
        s += ((f.maxLanes - 2).coerceAtLeast(0) * 5).coerceAtMost(15)
        s += (hazardCount * 8).coerceAtMost(24)
        if (f.hasHighway) s += 20
        if (f.peakHour) s += 8
        return s.toInt().coerceIn(0, 100)
    }

    fun tierOf(f: RouteFeatures, score: Int): Tier = when {
        f.hasHighway -> Tier.HIGHWAY
        f.merges > 0 || score >= 30 -> Tier.NATIONAL_ROAD
        else -> Tier.CITY
    }
}
