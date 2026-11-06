package kr.stepdrive.core.model

import kotlinx.serialization.Serializable

/** Something that happened at a place during a trip and that the report or the next practice can refer to. */
@Serializable
data class TripEvent(
    val kind: Kind,
    val tMs: Long,
    val location: LatLng? = null,
    val placeName: String = "",
    val detail: String = "",
) {
    enum class Kind {
        SIGNAL_OK, SIGNAL_MISSED, HARSH_BRAKE, HARSH_ACCEL, SPEEDING, HAZARD_SAFE, HAZARD_FAST, PARKING_DONE,
        /** Reference notices (never scored): gear handling, steering without a signal, driving far under the limit. */
        GEAR_NOTICE, LANE_NOTICE, SLOW_NOTICE, SIGNAL_MISMATCH_NOTICE,
        ROUTE_NOTICE, SENSOR_WARNING,
    }
}

@Serializable
data class TripScore(
    val tripId: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val distanceKm: Double,
    val tier: Tier,
    /** 0..100 before difficulty weighting. */
    val rawScore: Int,
    /** rawScore × tier weight, rounded; this is what accumulates. */
    val xp: Int,
    val signalRate: Double?,          // null when no maneuver needed a signal
    val harshBrakePer10Km: Double,
    val harshAccelPer10Km: Double,
    val speedingRatio: Double,
    val hazardSafeRatio: Double?,     // null when no hazard was passed
    val events: List<TripEvent>,
    val praise: String,
    val nextPractice: PracticePoint?,
    /** True when the trip gave too little to evaluate (no turns, no hazards, very short): shown, but not counted for tiers. */
    val provisional: Boolean = false,
    /** Previous practice point evaluated during this trip; absent in older saved records. */
    val reviewedPractice: PracticePoint? = null,
    /** True when the odometer did not advance and distance was estimated from fresh speed observations. */
    val distanceEstimated: Boolean = false,
    /** Only the latest 2,000 event details are retained; aggregate score counters remain complete. */
    val eventsTruncated: Boolean = false,
)

/** "What, where, by which criterion, and how we will verify it" — the next thing to practice. */
@Serializable
data class PracticePoint(
    val id: String,
    val kind: TripEvent.Kind,
    val title: String,
    val where: LatLng?,
    val placeName: String,
    val criterion: String,
    val createdAtMs: Long,
    val verified: Boolean? = null,
)
