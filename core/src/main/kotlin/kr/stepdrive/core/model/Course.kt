package kr.stepdrive.core.model

import kotlinx.serialization.Serializable

/** Difficulty tiers. Courses are gated by tier; drivers unlock tiers with accumulated score. */
enum class Tier(val label: String, val xpWeight: Double) {
    CITY("시내", 1.0),
    NATIONAL_ROAD("국도", 1.3),
    HIGHWAY("고속도로", 1.6);

    fun next(): Tier? = entries.getOrNull(ordinal + 1)
}

/** Static features of a route that make it harder or easier for a beginner. */
@Serializable
data class RouteFeatures(
    val intersections: Int = 0,
    val merges: Int = 0,
    val maxLanes: Int = 2,
    val hasHighway: Boolean = false,
    val peakHour: Boolean = false,
)

@Serializable
data class CourseCandidate(
    val id: String,
    val name: String,
    val destinationName: String,
    val distanceM: Int,
    val durationS: Int,
    val polyline: List<LatLng>,
    val features: RouteFeatures,
    /** Fixed tier for this candidate (e.g. the basic route to the driver's own destination is always open). */
    val tierOverride: Tier? = null,
    /** False for a destination without provider-supplied road geometry and route details. */
    val detailsVerified: Boolean = true,
)

/** A candidate after evaluation: hazards on it, difficulty score, tier, and the reason we show the driver. */
@Serializable
data class EvaluatedCourse(
    val candidate: CourseCandidate,
    val hazardsOnRoute: List<Hazard>,
    val difficultyScore: Int,
    val tier: Tier,
    val recommended: Boolean,
    val reason: String,
)
