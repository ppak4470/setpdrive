package kr.stepdrive.core.coach

import kr.stepdrive.core.model.NavState
import kr.stepdrive.core.model.VehicleSample
import kr.stepdrive.core.model.freshAt

/**
 * Dead reckoning between navigation updates: while the navigation reports the same maneuver distance,
 * the car's own speed is integrated to keep the remaining distance moving. The moment the navigation
 * sends a new value, that value wins. Keeps the display (and the coach's timing) smooth when GPS
 * updates are slow — and makes the emulator, whose GPS does not move with the pedals, behave like a car.
 */
class DistanceSmoother(private val maxGapS: Double = 3.0) {
    private var lastKey: String? = null
    private var lastTMs = 0L
    private var travelledM = 0.0

    fun apply(nav: NavState, sample: VehicleSample): NavState {
        val next = nav.next
        val key = next?.let { "${nav.routeRevision}|${it.type}|${it.description}|${it.distanceM}|${nav.remainDistanceM}" }
        if (key == null) { lastKey = null; travelledM = 0.0; return nav }
        if (key != lastKey) {           // fresh value from the navigation: resync
            lastKey = key
            lastTMs = sample.tMs
            travelledM = 0.0
            return nav
        }
        val dt = (sample.tMs - lastTMs) / 1000.0
        lastTMs = sample.tMs
        val v = (sample.freshAt().speedKmh ?: 0f) / 3.6
        if (dt > 0.0 && dt <= maxGapS && v > 0.3) travelledM += v * dt
        if (travelledM <= 0.0) return nav
        return nav.copy(
            maneuvers = nav.maneuvers.mapIndexed { i, m -> if (i == 0) m.copy(distanceM = (m.distanceM - travelledM).coerceAtLeast(0.0)) else m },
            remainDistanceM = nav.remainDistanceM?.let { (it - travelledM).toInt().coerceAtLeast(0) },
            accidentProneAheadM = nav.accidentProneAheadM?.let { (it - travelledM).toInt().coerceAtLeast(0) },
        )
    }
}
