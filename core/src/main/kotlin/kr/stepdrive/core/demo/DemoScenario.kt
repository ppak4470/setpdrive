package kr.stepdrive.core.demo

import kr.stepdrive.core.model.CourseCandidate
import kr.stepdrive.core.model.Gear
import kr.stepdrive.core.model.Hazard
import kr.stepdrive.core.model.LatLng
import kr.stepdrive.core.model.Maneuver
import kr.stepdrive.core.model.ManeuverType
import kr.stepdrive.core.model.NavState
import kr.stepdrive.core.model.NavStatus
import kr.stepdrive.core.model.RouteFeatures
import kr.stepdrive.core.model.TurnSignal
import kr.stepdrive.core.model.VehicleSample

/** One frame of the scripted demo: what the car and the navigation report at second [t]. */
data class DemoFrame(val t: Int, val sample: VehicleSample, val nav: NavState, val caption: String = "")

/**
 * A ~2 minute scripted drive for the emulator (no real driving needed):
 * start → right turn with signal → accident-prone intersection → highway merge → arrive → rear parking.
 * Coordinates are around Seoul; they only need to be self-consistent.
 */
object DemoScenario {
    val hazards = listOf(
        Hazard("H-1", "성수사거리", LatLng(37.5445, 127.0560), radiusM = 60f, accidentCount = 14, casualtyCount = 21, year = 2023),
        Hazard("H-2", "뚝섬역 교차로", LatLng(37.5472, 127.0473), radiusM = 60f, accidentCount = 9, casualtyCount = 12, year = 2023),
        Hazard("H-3", "영동대교 북단", LatLng(37.5340, 127.0610), radiusM = 80f, accidentCount = 17, casualtyCount = 25, year = 2023),
    )

    /** Two ways to the office: the fastest one crosses two hazards; the recommended one avoids them. */
    val courseCandidates = listOf(
        CourseCandidate(
            id = "fast", name = "가장 빠른 길", destinationName = "회사", distanceM = 6_900, durationS = 22 * 60,
            polyline = listOf(LatLng(37.5410, 127.0450), LatLng(37.5445, 127.0560), LatLng(37.5472, 127.0473), LatLng(37.5500, 127.0400)),
            features = RouteFeatures(intersections = 9, merges = 1, maxLanes = 6),
        ),
        CourseCandidate(
            id = "safe", name = "연습 코스", destinationName = "회사", distanceM = 7_200, durationS = 26 * 60,
            polyline = listOf(LatLng(37.5410, 127.0450), LatLng(37.5380, 127.0380), LatLng(37.5460, 127.0330), LatLng(37.5500, 127.0400)),
            features = RouteFeatures(intersections = 7, merges = 0, maxLanes = 3),
        ),
        CourseCandidate(
            id = "highway", name = "강변북로 코스", destinationName = "회사", distanceM = 9_800, durationS = 24 * 60,
            polyline = listOf(LatLng(37.5410, 127.0450), LatLng(37.5340, 127.0610), LatLng(37.5500, 127.0400)),
            features = RouteFeatures(intersections = 3, merges = 2, maxLanes = 4, hasHighway = true),
        ),
    )

    private val start = LatLng(37.5410, 127.0450)
    private val hazardApproach = LatLng(37.5436, 127.0548) // ~130 m before H-1

    fun frames(): List<DemoFrame> {
        val f = mutableListOf<DemoFrame>()
        fun add(t: Int, speed: Float?, gear: Gear, signal: TurnSignal = TurnSignal.OFF, steer: Float = 0f, odo: Float,
                nav: NavState, caption: String = "") {
            f += DemoFrame(t, VehicleSample(t * 1000L, speed, gear, signal, brakePressed = (speed ?: 0f) < 1f, steeringAngleDeg = steer, odometerKm = odo), nav, caption)
        }
        val routeNav = { maneuvers: List<Maneuver>, remain: Int, loc: LatLng, heading: Double, limit: Int, caution: Int? ->
            NavState(NavStatus.ROUTE_DRIVE, maneuvers, remain, remain / 8, loc, heading, limit, caution)
        }
        var odo = 12_345.0f
        var t = 0
        // 0–5 s: parked, route set
        repeat(6) { add(t++, 0f, Gear.PARK, odo = odo, nav = routeNav(listOf(Maneuver(ManeuverType.RIGHT, 420.0, "성수이로 우회전")), 7200, start, 45.0, 50, null), caption = "출발 전") }
        // 6–30 s: accelerate, right turn ahead; signal on at 24 s (~110 m before)
        for (i in 0 until 25) {
            val speed = (i * 2.2f).coerceAtMost(42f)
            val dist = 420.0 - i * 13.0
            val signal = if (dist <= 140) TurnSignal.RIGHT else TurnSignal.OFF
            odo += speed / 3600f
            add(t++, speed, Gear.DRIVE, signal, odo = odo, nav = routeNav(listOf(Maneuver(ManeuverType.RIGHT, dist.coerceAtLeast(5.0), "성수이로 우회전")), 7200 - i * 13, start, 45.0, 50, null), caption = "우회전 준비")
        }
        // 31–52 s: after the turn, approach the accident-prone intersection, slow down
        for (i in 0 until 22) {
            val dist = 520.0 - i * 24.0
            val speed = if (dist < 300) (46f - (300 - dist.toFloat()) * 0.06f).coerceAtLeast(28f) else 46f
            odo += speed / 3600f
            val loc = if (dist < 150) hazardApproach else LatLng(37.5428, 127.0520)
            add(t++, speed, Gear.DRIVE, odo = odo, nav = routeNav(listOf(Maneuver(ManeuverType.STRAIGHT, dist, "직진")), 6400 - i * 24, loc, 60.0, 50, dist.toInt()), caption = "위험 구간 접근")
        }
        // 53–56 s: inside the hazard zone at a safe speed
        repeat(4) { odo += 28f / 3600f; add(t++, 28f, Gear.DRIVE, odo = odo, nav = routeNav(listOf(Maneuver(ManeuverType.STRAIGHT, 900.0, "직진")), 5800, hazards[0].center, 60.0, 50, null), caption = "사고다발 교차로 통과") }
        // 57–80 s: highway merge, right signal
        for (i in 0 until 24) {
            val dist = 700.0 - i * 30.0
            val speed = (60f + i * 1.5f).coerceAtMost(90f)
            val signal = if (dist in 60.0..260.0) TurnSignal.RIGHT else TurnSignal.OFF
            odo += speed / 3600f
            add(t++, speed, Gear.DRIVE, signal, odo = odo, nav = routeNav(listOf(Maneuver(ManeuverType.MERGE_RIGHT, dist.coerceAtLeast(5.0), "강변북로 합류")), 5000 - i * 30, LatLng(37.5380, 127.0600), 90.0, 80, null), caption = "고속도로 합류")
        }
        // 81–95 s: decelerate and arrive
        for (i in 0 until 15) {
            val speed = (90f - i * 6.5f).coerceAtLeast(0f)
            odo += speed / 3600f
            add(t++, speed, Gear.DRIVE, odo = odo, nav = routeNav(listOf(Maneuver(ManeuverType.ARRIVE, (300.0 - i * 20).coerceAtLeast(0.0), "회사 도착")), (300 - i * 20).coerceAtLeast(0), LatLng(37.5500, 127.0400), 0.0, 30, null), caption = "도착")
        }
        // 96–125 s: rear parking in reverse with steering
        val parkedNav = NavState(NavStatus.FREE_DRIVE, emptyList(), 0, 0, LatLng(37.5500, 127.0400), 0.0, null, null)
        repeat(3) { add(t++, 0f, Gear.REVERSE, odo = odo, nav = parkedNav, caption = "주차 1단계") }
        for (i in 0 until 6) add(t++, 2.5f, Gear.REVERSE, steer = (i + 1) * 90f, odo = odo, nav = parkedNav, caption = "핸들 끝까지")
        add(t++, 7f, Gear.REVERSE, steer = 540f, odo = odo, nav = parkedNav, caption = "조금 빠름")
        for (i in 0 until 5) add(t++, 3f, Gear.REVERSE, steer = 540f, odo = odo, nav = parkedNav, caption = "후진 중")
        for (i in 0 until 6) add(t++, 2f, Gear.REVERSE, steer = 540f - (i + 1) * 90f, odo = odo, nav = parkedNav, caption = "핸들 풀기")
        repeat(3) { add(t++, 0f, Gear.REVERSE, steer = 4f, odo = odo, nav = parkedNav, caption = "정지") }
        repeat(3) { add(t++, 0f, Gear.PARK, steer = 4f, odo = odo, nav = parkedNav, caption = "주차 완료") }
        return f
    }
}
