package kr.stepdrive.core.practice

import kr.stepdrive.core.hazard.Geo
import kr.stepdrive.core.model.NavState
import kr.stepdrive.core.model.PracticePoint
import kr.stepdrive.core.model.TripEvent
import kr.stepdrive.core.model.TripScore
import kr.stepdrive.core.model.TurnSignal
import kr.stepdrive.core.model.VehicleSample

/** Collects position and signal evidence for the previous practice point during one trip. */
class PracticeProgress(private val point: PracticePoint?, private val radiusM: Double = 150.0) {
    private var passedNear = false
    private var available = true
    private var speedObservations = 0
    private var lastSpeedTMs: Long? = null

    fun onTick(sample: VehicleSample, nav: NavState) {
        val target = point?.where ?: return
        val position = nav.location ?: return
        if (Geo.distanceM(position, target) > radiusM) return
        passedNear = true
        available = available && when (point.kind) {
            TripEvent.Kind.SIGNAL_MISSED -> sample.turnSignal != TurnSignal.UNKNOWN
            TripEvent.Kind.SPEEDING -> sample.speedKmh != null && nav.speedLimitKmh != null
            TripEvent.Kind.HARSH_BRAKE, TripEvent.Kind.HARSH_ACCEL, TripEvent.Kind.HAZARD_FAST -> sample.speedKmh != null
            else -> false
        }
        if (sample.speedKmh != null && lastSpeedTMs != sample.speedTMs) {
            speedObservations++
            lastSpeedTMs = sample.speedTMs
        }
    }

    fun review(trip: TripScore): PracticePoint? {
        val target = point ?: return null
        if (!passedNear || !available || trip.provisional) return target.copy(verified = null)
        val qualifyingKinds = when (target.kind) {
            TripEvent.Kind.SIGNAL_MISSED -> setOf(TripEvent.Kind.SIGNAL_OK, TripEvent.Kind.SIGNAL_MISSED)
            TripEvent.Kind.HAZARD_FAST -> setOf(TripEvent.Kind.HAZARD_SAFE, TripEvent.Kind.HAZARD_FAST)
            else -> emptySet()
        }
        if (qualifyingKinds.isNotEmpty() && trip.events.none { event ->
                event.kind in qualifyingKinds && event.location != null && target.where != null &&
                    Geo.distanceM(event.location, target.where) <= radiusM
            }) return target.copy(verified = null)
        if (target.kind in setOf(TripEvent.Kind.HARSH_BRAKE, TripEvent.Kind.HARSH_ACCEL) && speedObservations < 2) {
            return target.copy(verified = null)
        }
        return target.copy(verified = PracticePointGenerator().verify(target, trip, radiusM, passedNear = true))
    }
}
