package kr.stepdrive.core.score

import kr.stepdrive.core.hazard.HazardIndex
import kr.stepdrive.core.model.LatLng
import kr.stepdrive.core.model.Maneuver
import kr.stepdrive.core.model.NavState
import kr.stepdrive.core.model.Tier
import kr.stepdrive.core.model.TripEvent
import kr.stepdrive.core.model.TripScore
import kr.stepdrive.core.model.TurnSignal
import kr.stepdrive.core.model.VehicleSample
import kr.stepdrive.core.model.requiredSignal
import kr.stepdrive.core.model.freshAt
import kr.stepdrive.core.model.Gear
import kr.stepdrive.core.model.NavStatus
import kr.stepdrive.core.practice.PracticePointGenerator
import kotlin.math.abs

/**
 * Consumes vehicle samples and navigation states during one trip and produces a [TripScore] at the end.
 *
 * Thresholds (m/s²) follow the values commonly used for "harsh" events; they are tunable.
 */
class ScoreEngine(
    private val tripId: String,
    private val tier: Tier,
    private val hazards: HazardIndex,
    private val harshBrakeMps2: Double = -3.5,
    private val harshAccelMps2: Double = 3.0,
    private val speedingToleranceKmh: Int = 10,
    private val signalLeadMinM: Double = 30.0,
) {
    private var startedAt = 0L
    private var lastSample: VehicleSample? = null
    private var lastEventAt = mutableMapOf<TripEvent.Kind, Long>()
    private var startOdo: Float? = null
    private var lastOdo: Float? = null
    private var observedDistanceKm = 0.0
    private var movingSamples = 0
    private var speedingSamples = 0
    private var validSpeedObservations = 0
    private val events = ArrayDeque<TripEvent>()
    private val eventCounts = mutableMapOf<TripEvent.Kind, Int>()
    private var eventsTruncated = false
    private var lastLocation: LatLng? = null

    // Maneuver tracking for signal checks.
    private var currentManeuver: Maneuver? = null
    private var signalSeenForCurrent = false
    private var signalObservedForCurrent = false
    private var signalUnavailableForCurrent = false
    private var signalNeeded = 0
    private var signalOk = 0
    private var routeRevision: Long? = null

    // Hazard tracking.
    private var insideHazardId: String? = null
    private var hazardsPassed = 0
    private var hazardsSafe = 0

    fun onTick(sample: VehicleSample, navigation: NavState) {
        val s = sample.freshAt()
        val nav = navigation.freshAt(s.tMs)
        if (startedAt == 0L) startedAt = s.tMs
        nav.location?.let { lastLocation = it }
        s.odometerKm?.takeIf { it.isFinite() && it >= 0 }?.let { if (startOdo == null) startOdo = it; lastOdo = it }
        if (s.speedKmh != null && s.speedTMs != lastSample?.speedTMs) validSpeedObservations++

        trackDistance(s)
        trackDynamics(s)
        trackSpeeding(s, nav)
        trackManeuver(s, nav)
        trackHazards(s, nav)
        lastSample = s
    }

    private fun trackDistance(s: VehicleSample) {
        val previous = lastSample ?: return
        val before = previous.speedKmh ?: return
        val after = s.speedKmh ?: return
        val elapsedMs = s.speedTMs - previous.speedTMs
        if (elapsedMs !in 1..3_000) return
        // Integrate each fresh speed observation once; do not bridge a signal outage.
        observedDistanceKm += (abs(before.toDouble()) + abs(after.toDouble())) / 2 * elapsedMs / 3_600_000
    }

    private fun trackDynamics(s: VehicleSample) {
        val prev = lastSample ?: return
        val v0 = prev.speedKmh ?: return
        val v1 = s.speedKmh ?: return
        val dt = (s.speedTMs - prev.speedTMs) / 1000.0
        if (dt <= 0.0 || dt > 3.0) return
        val a = ((v1 - v0) / 3.6) / dt
        if (a <= harshBrakeMps2) record(TripEvent.Kind.HARSH_BRAKE, s.tMs, "감속 %.1f m/s²".format(a), debounceMs = 2_000)
        if (a >= harshAccelMps2) record(TripEvent.Kind.HARSH_ACCEL, s.tMs, "가속 %.1f m/s²".format(a), debounceMs = 2_000)
    }

    private fun trackSpeeding(s: VehicleSample, nav: NavState) {
        val v = s.speedKmh ?: return
        if (v < 5f) return
        val limit = nav.speedLimitKmh ?: return
        if (limit <= 0 || s.speedTMs == lastSample?.speedTMs) return
        movingSamples++
        if (v > limit + speedingToleranceKmh) {
            speedingSamples++
            record(TripEvent.Kind.SPEEDING, s.tMs, "제한 ${limit}km/h 구간에서 ${v.toInt()}km/h", debounceMs = 10_000)
        }
    }

    private fun trackManeuver(s: VehicleSample, nav: NavState) {
        if (routeRevision != nav.routeRevision) { currentManeuver = null; routeRevision = nav.routeRevision }
        val next = nav.next.takeIf { nav.status == NavStatus.ROUTE_DRIVE }
        val cur = currentManeuver
        val changed = cur != null && (next == null || next.type != cur.type ||
            next.description != cur.description || next.distanceM > cur.distanceM + 80)
        if (changed) {
            // A route replacement far from the turn, or cancelled guidance, proves no completion.
            if (nav.status == NavStatus.ROUTE_DRIVE && cur!!.distanceM < 40) finishManeuver(cur, s.tMs)
            currentManeuver = null
        }
        if (next != null && (cur == null || changed)) {
            currentManeuver = next
            signalSeenForCurrent = false
            signalObservedForCurrent = false
            signalUnavailableForCurrent = false
        } else if (next != null) {
            currentManeuver = next
        }
        val need = currentManeuver?.type?.requiredSignal ?: return
        val distance = currentManeuver!!.distanceM
        if (distance !in 0.0..600.0) return
        if (s.gear != Gear.DRIVE) return
        if (s.speedKmh == null) {
            if (signalObservedForCurrent) signalUnavailableForCurrent = true
            return
        }
        if (abs(s.speedKmh) <= 0.5f) return
        // A missing signal or an observation starting too late cannot establish a missed signal.
        if (s.turnSignal == TurnSignal.UNKNOWN) signalUnavailableForCurrent = true
        if (s.turnSignal != TurnSignal.UNKNOWN && distance >= signalLeadMinM) {
            signalObservedForCurrent = true
            signalSeenForCurrent = s.turnSignal == need
        }
        // The opposite indicator invalidates an earlier success even inside the lead distance.
        if (s.turnSignal in setOf(TurnSignal.LEFT, TurnSignal.RIGHT) && s.turnSignal != need)
            signalSeenForCurrent = false
    }

    private fun finishManeuver(m: Maneuver, tMs: Long) {
        val need = m.type.requiredSignal ?: return
        if (!signalObservedForCurrent || signalUnavailableForCurrent) return
        signalNeeded++
        val label = if (need == TurnSignal.LEFT) "좌회전" else "우회전"
        if (signalSeenForCurrent) {
            signalOk++
            record(TripEvent.Kind.SIGNAL_OK, tMs, "$label 깜빡이 미리 켬", debounceMs = 0)
        } else {
            record(TripEvent.Kind.SIGNAL_MISSED, tMs, "$label 전 안내 방향 깜빡이 사용 미확인", debounceMs = 0, placeName = m.description)
        }
    }

    private fun trackHazards(s: VehicleSample, nav: NavState) {
        val loc = nav.location ?: return
        val inside = hazards.contains(loc)
        if (inside != null && insideHazardId != inside.id) {
            insideHazardId = inside.id
            // Unknown entry speed is unevaluated, even if speed recovers later inside the zone.
            val v = s.speedKmh ?: return
            hazardsPassed++
            if (v <= 30f) {
                hazardsSafe++
                record(TripEvent.Kind.HAZARD_SAFE, s.tMs, "${inside.name} 진입 속도 ${v.toInt()}km/h", debounceMs = 0, placeName = inside.name)
            } else {
                record(TripEvent.Kind.HAZARD_FAST, s.tMs, "${inside.name} 진입 속도 ${v.toInt()}km/h", debounceMs = 0, placeName = inside.name)
            }
        } else if (inside == null) {
            insideHazardId = null
        }
    }

    private fun record(kind: TripEvent.Kind, tMs: Long, detail: String, debounceMs: Long, placeName: String = "") {
        val last = lastEventAt[kind]
        if (debounceMs > 0 && last != null && tMs - last < debounceMs) return
        lastEventAt[kind] = tMs
        eventCounts[kind] = (eventCounts[kind] ?: 0) + 1
        if (events.size == 2_000) { events.removeFirst(); eventsTruncated = true }
        events.addLast(TripEvent(kind, tMs, lastLocation, placeName.take(120), detail.take(512)))
    }

    fun markParkingDone(tMs: Long) = record(TripEvent.Kind.PARKING_DONE, tMs, "주차 완료", debounceMs = 0)

    /** Reference notice from the coach or the operation watch: logged with the place, never scored. */
    fun note(kind: TripEvent.Kind, tMs: Long, detail: String, placeName: String = "") = record(kind, tMs, detail, debounceMs = 0, placeName = placeName)

    fun finish(endMs: Long, practiceGenerator: PracticePointGenerator = PracticePointGenerator()): TripScore {
        currentManeuver?.let { if (it.type.requiredSignal != null && it.distanceM < 40) finishManeuver(it, endMs) }
        currentManeuver = null
        return summary(endMs, practiceGenerator)
    }

    /** A checkpoint never consumes a pending maneuver or awards XP for an interrupted trip. */
    fun checkpoint(endMs: Long): TripScore = summary(endMs, PracticePointGenerator()).copy(
        provisional = true, xp = 0, nextPractice = null, praise = "중단된 연습의 관측 기록이에요. 누적 점수에는 반영하지 않았어요")

    private fun summary(endMs: Long, practiceGenerator: PracticePointGenerator): TripScore {
        val measuredKm = ((lastOdo ?: 0f) - (startOdo ?: 0f)).toDouble()
        val distanceEstimated = measuredKm <= 0
        val distanceKm = if (distanceEstimated) observedDistanceKm else measuredKm
        // Keep the scoring denominator bounded without inventing distance in the report.
        val per10 = { n: Int -> n / distanceKm.coerceAtLeast(0.1) * 10.0 }
        val brakes = eventCounts[TripEvent.Kind.HARSH_BRAKE] ?: 0
        val accels = eventCounts[TripEvent.Kind.HARSH_ACCEL] ?: 0
        val signalRate = if (signalNeeded == 0) null else signalOk.toDouble() / signalNeeded
        val speedingRatio = if (movingSamples == 0) 0.0 else speedingSamples.toDouble() / movingSamples
        val hazardSafeRatio = if (hazardsPassed == 0) null else hazardsSafe.toDouble() / hazardsPassed

        var score = 100.0
        signalRate?.let { score -= (1 - it) * 30 }
        score -= (per10(brakes) * 6).coerceAtMost(25.0)
        score -= (per10(accels) * 4).coerceAtMost(15.0)
        score -= (speedingRatio * 40).coerceAtMost(20.0)
        hazardSafeRatio?.let { score -= (1 - it) * 10 }
        val raw = score.toInt().coerceIn(0, 100)
        // No evaluated maneuver/hazard plus a short trip or too few speed observations: shown, not counted.
        val provisional = signalNeeded == 0 && hazardsPassed == 0 && (distanceKm < 1.0 || validSpeedObservations < 2)

        val trip = TripScore(
            tripId = tripId,
            startedAtMs = startedAt,
            endedAtMs = endMs,
            distanceKm = distanceKm,
            distanceEstimated = distanceEstimated,
            tier = tier,
            rawScore = raw,
            xp = if (provisional) 0 else (raw * tier.xpWeight).toInt(),
            signalRate = signalRate,
            harshBrakePer10Km = per10(brakes),
            harshAccelPer10Km = per10(accels),
            speedingRatio = speedingRatio,
            hazardSafeRatio = hazardSafeRatio,
            events = events.toList(),
            eventsTruncated = eventsTruncated,
            praise = if (provisional) "주행 길이나 관측 정보가 부족해 점수를 보류했어요" else praiseFor(signalRate, brakes, hazardSafeRatio),
            nextPractice = null,
            provisional = provisional,
        )
        return trip.copy(nextPractice = practiceGenerator.generate(trip))
    }

    private fun praiseFor(signalRate: Double?, brakes: Int, hazardSafe: Double?): String = when {
        signalRate == 1.0 && signalNeeded > 0 -> "회전 ${signalNeeded}번 모두 깜빡이를 미리 켰어요"
        brakes == 0 && movingSamples > 30 -> "급제동 없이 부드럽게 달렸어요"
        hazardSafe == 1.0 -> "위험 구간을 모두 안전한 속도로 지났어요"
        signalRate != null && signalRate >= 0.7 -> "깜빡이 습관이 자리 잡고 있어요"
        else -> "오늘도 안전하게 도착했어요"
    }

    companion object {
        fun describeSpeed(speedKmh: Float?): String = speedKmh?.let { "${abs(it).toInt()}km/h" } ?: "-"
    }
}
