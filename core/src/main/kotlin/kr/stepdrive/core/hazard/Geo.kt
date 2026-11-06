package kr.stepdrive.core.hazard

import kr.stepdrive.core.model.LatLng
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle distance in meters. */
    fun distanceM(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    /** Initial bearing from [from] to [to] in degrees 0..360. */
    fun bearingDeg(from: LatLng, to: LatLng): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLng = Math.toRadians(to.lng - from.lng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Smallest absolute difference between two bearings, 0..180. */
    fun bearingDiff(a: Double, b: Double): Double {
        val d = ((a - b) % 360.0 + 540.0) % 360.0 - 180.0
        return kotlin.math.abs(d)
    }

    /** Distance from point [p] to the segment [a]-[b], approximated on a local flat plane (fine for < a few km). */
    fun distanceToSegmentM(p: LatLng, a: LatLng, b: LatLng): Double {
        val kx = 111_320.0 * cos(Math.toRadians(p.lat))
        val ky = 110_540.0
        val px = (p.lng - a.lng) * kx; val py = (p.lat - a.lat) * ky
        val bx = (b.lng - a.lng) * kx; val by = (b.lat - a.lat) * ky
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - t * bx; val dy = py - t * by
        return sqrt(dx * dx + dy * dy)
    }
}
