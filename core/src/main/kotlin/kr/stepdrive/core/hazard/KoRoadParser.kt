package kr.stepdrive.core.hazard

import kotlinx.serialization.json.*
import kr.stepdrive.core.model.*

data class HazardPage(val items: List<Hazard>, val total: Int)
object KoRoadParser {
    fun parse(body: String, year: Int): HazardPage {
        require(body.length <= 3_000_000) { "응답 크기 초과" }
        val top = Json.parseToJsonElement(body).jsonObject
        val response = top["response"] as? JsonObject
        val root = response?.get("body") as? JsonObject ?: top
        val header = response?.get("header") as? JsonObject ?: top
        require(header["resultCode"]?.jsonPrimitive?.content in setOf("00", "0")) { "공공데이터 응답 오류" }
        val total = root["totalCount"]?.jsonPrimitive?.intOrNull ?: error("전체 건수 누락")
        require(total in 0..10_000)
        val node = (root["items"] as? JsonObject)?.get("item") ?: root["items"]
        val records = when (node) {
            is JsonArray -> node
            is JsonObject -> JsonArray(listOf(node))
            else -> if (total == 0) JsonArray(emptyList()) else error("목록 누락")
        }
        val items = records.map { item ->
            val o = item.jsonObject
            val lat = o["la_crd"]?.jsonPrimitive?.doubleOrNull ?: error("위도 누락")
            val lng = o["lo_crd"]?.jsonPrimitive?.doubleOrNull ?: error("경도 누락")
            require(lat.isFinite() && lng.isFinite() && lat in 32.0..40.0 && lng in 123.0..133.0)
            fun count(key: String) = (o[key]?.jsonPrimitive?.intOrNull ?: 0).also { require(it >= 0) }
            Hazard(o["afos_fid"]?.jsonPrimitive?.content ?: "$lat,$lng",
                o["spot_nm"]?.jsonPrimitive?.content?.take(120) ?: "사고다발지역", LatLng(lat, lng),
                radiusM = 150f, accidentCount = count("occrrnc_cnt"), casualtyCount = count("caslt_cnt"), year = year)
        }
        return HazardPage(items, total)
    }
}
