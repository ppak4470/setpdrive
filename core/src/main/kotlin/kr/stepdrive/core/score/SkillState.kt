package kr.stepdrive.core.score

import kotlinx.serialization.Serializable
import kr.stepdrive.core.model.Tier
import kr.stepdrive.core.model.TripScore

/** Thresholds are provisional; they are adjusted after emulator and small-group testing. */
@Serializable
data class SkillRules(
    val xpToUnlockNationalRoad: Int = 300,
    val xpToUnlockHighway: Int = 800,
    val minRecentAverage: Int = 70,
    val minSignalRate: Double = 0.7,
    val recentWindow: Int = 5,
    val graduationSkill: Int = 80,
    val graduationHighwayTrips: Int = 2,
    /** Fading feedback: this many consecutive trips at or above [fadeScore] lowers the feedback level by one. */
    val fadeScore: Int = 90,
    val fadeStreak: Int = 2,
    /** A trip under this score restores one level. */
    val restoreBelow: Int = 80,
)

/** What accumulates for one driver profile across trips. */
@Serializable
data class SkillState(
    val xp: Int = 0,
    val trips: Int = 0,
    val highwayTrips: Int = 0,
    val recentScores: List<Int> = emptyList(),
    val recentSignalRates: List<Double> = emptyList(),
    val rules: SkillRules = SkillRules(),
    /** 3 = full explanations, 2 = short, 1 = warnings and turn announcements only. */
    val feedbackLevel: Int = 3,
    val highStreak: Int = 0,
) {
    val recentAverage: Int get() = if (recentScores.isEmpty()) 0 else recentScores.average().toInt()
    val signalRate: Double? get() = recentSignalRates.takeIf { it.isNotEmpty() }?.average()

    /** The highest tier whose courses are open to this driver. */
    fun unlockedTier(): Tier {
        val qualityOk = recentAverage >= rules.minRecentAverage && (signalRate ?: 0.0) >= rules.minSignalRate
        return when {
            xp >= rules.xpToUnlockHighway && qualityOk -> Tier.HIGHWAY
            xp >= rules.xpToUnlockNationalRoad && qualityOk -> Tier.NATIONAL_ROAD
            else -> Tier.CITY
        }
    }

    /** XP still needed for the next tier, or null when every tier is open. */
    fun xpToNextTier(): Int? = when (unlockedTier()) {
        Tier.CITY -> (rules.xpToUnlockNationalRoad - xp).coerceAtLeast(0)
        Tier.NATIONAL_ROAD -> (rules.xpToUnlockHighway - xp).coerceAtLeast(0)
        Tier.HIGHWAY -> null
    }

    fun graduated(): Boolean =
        unlockedTier() == Tier.HIGHWAY && recentAverage >= rules.graduationSkill && highwayTrips >= rules.graduationHighwayTrips

    fun apply(trip: TripScore): SkillState {
        if (trip.provisional) return copy(trips = trips + 1)
        // Fading feedback (proposal ⑧): two 90+ trips in a row → one level quieter; under 80 → one level back.
        var level = feedbackLevel.coerceIn(1, 3)
        var streak = highStreak
        when {
            trip.rawScore >= rules.fadeScore -> { streak += 1; if (streak >= rules.fadeStreak) { level = (level - 1).coerceAtLeast(1); streak = 0 } }
            trip.rawScore < rules.restoreBelow -> { level = (level + 1).coerceAtMost(3); streak = 0 }
            else -> streak = 0
        }
        return copy(
            xp = xp + trip.xp,
            trips = trips + 1,
            highwayTrips = highwayTrips + if (trip.tier == Tier.HIGHWAY) 1 else 0,
            recentScores = (recentScores + trip.rawScore).takeLast(rules.recentWindow),
            recentSignalRates = (trip.signalRate?.let { recentSignalRates + it } ?: recentSignalRates).takeLast(rules.recentWindow),
            feedbackLevel = level,
            highStreak = streak,
        )
    }
}
