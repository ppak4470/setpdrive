package kr.stepdrive.core.hazard

import kr.stepdrive.core.model.Hazard
import kr.stepdrive.core.model.LatLng

data class HazardAhead(val hazard: Hazard, val distanceM: Double)

/** Answers "which accident-prone zones are on this route?" and "is one coming up ahead of me?". */
class HazardIndex(private val hazards: List<Hazard>) {

    /** Hazards whose zone lies within [marginM] of any polyline segment. */
    fun onRoute(polyline: List<LatLng>, marginM: Double = 40.0): List<Hazard> {
        if (polyline.size < 2) return emptyList()
        return hazards.filter { h ->
            polyline.zipWithNext().any { (a, b) ->
                Geo.distanceToSegmentM(h.center, a, b) <= h.radiusM + marginM
            }
        }
    }

    /**
     * Nearest hazard in front of the driver: within [maxM], and within ±[coneDeg] of the heading.
     * Without a heading every direction counts.
     */
    fun nearestAhead(at: LatLng, headingDeg: Double?, maxM: Double = 800.0, coneDeg: Double = 70.0): HazardAhead? =
        hazards.asSequence()
            .map { HazardAhead(it, Geo.distanceM(at, it.center) - it.radiusM) }
            .filter { it.distanceM <= maxM }
            .filter { headingDeg == null || Geo.bearingDiff(headingDeg, Geo.bearingDeg(at, it.hazard.center)) <= coneDeg }
            .minByOrNull { it.distanceM }

    fun contains(at: LatLng): Hazard? = hazards.firstOrNull { Geo.distanceM(at, it.center) <= it.radiusM }
}
