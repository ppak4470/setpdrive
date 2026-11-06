package kr.stepdrive.core.model

import kotlinx.serialization.Serializable

/**
 * An accident-prone zone. Shaped after the Korea Road Traffic Authority "교통사고다발지역" public data
 * (point coordinate + accident counts); the radius is ours because the data ships polygons we simplify.
 */
@Serializable
data class Hazard(
    val id: String,
    val name: String,
    val center: LatLng,
    val radiusM: Float = 60f,
    val accidentCount: Int = 0,
    val casualtyCount: Int = 0,
    val year: Int = 0,
)
