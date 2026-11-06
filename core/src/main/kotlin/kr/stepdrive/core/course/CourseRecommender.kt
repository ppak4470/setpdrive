package kr.stepdrive.core.course

import kr.stepdrive.core.hazard.HazardIndex
import kr.stepdrive.core.model.CourseCandidate
import kr.stepdrive.core.model.EvaluatedCourse
import kr.stepdrive.core.model.Tier

/**
 * Picks the practice course for a driver: the safest route whose tier the driver has unlocked.
 * A route is never forced on the driver; the result carries the reason so the UI and the voice can explain it.
 */
class CourseRecommender(private val hazards: HazardIndex) {

    fun evaluate(candidates: List<CourseCandidate>, driverTier: Tier): List<EvaluatedCourse> {
        if (candidates.isEmpty()) return emptyList()
        val scored = candidates.map { c ->
            val onRoute = if (c.detailsVerified) hazards.onRoute(c.polyline) else emptyList()
            val score = CourseDifficulty.score(c.features, onRoute.size)
            Triple(c, onRoute, score)
        }
        val fastest = scored.minBy { it.first.durationS }
        val allowed = scored.filter { it.first.detailsVerified && (it.first.tierOverride ?: CourseDifficulty.tierOf(it.first.features, it.third)) <= driverTier }
        // Fewest hazards first, then easiest, then quickest. A locked course is never the recommendation.
        val best = allowed.sortedWith(compareBy({ it.second.size }, { it.third }, { it.first.durationS })).firstOrNull()

        return scored.map { (c, onRoute, score) ->
            val tier = c.tierOverride ?: CourseDifficulty.tierOf(c.features, score)
            val isBest = best != null && c.id == best.first.id
            val reason = when {
                !c.detailsVerified -> "목적지만 확인했어요. 실제 거리·도로 난이도·위험 구간은 경로 정보 수신 전이라 알 수 없어요."
                isBest && c.id == fastest.first.id -> "가장 빠른 길이면서 위험 구간이 가장 적어요."
                isBest -> {
                    val extraMin = ((c.durationS - fastest.first.durationS) / 60.0).let { kotlin.math.ceil(it).toInt() }
                    val avoided = (fastest.second.size - onRoute.size).coerceAtLeast(0)
                    buildString {
                        if (extraMin > 0) append("${extraMin}분 더 걸리지만 ")
                        if (avoided > 0) append("사고가 잦은 교차로 ${avoided}곳을 피해요.")
                        else append("지금 실력에 맞는 길이에요.")
                    }
                }
                tier > driverTier -> "지금 실력보다 어려운 ${tier.label} 수준이에요. 점수를 더 쌓으면 열려요."
                onRoute.isNotEmpty() -> "사고다발지역 ${onRoute.size}곳을 지나요."
                else -> ""
            }
            EvaluatedCourse(c, onRoute, score, tier, isBest, reason)
        }
    }
}
