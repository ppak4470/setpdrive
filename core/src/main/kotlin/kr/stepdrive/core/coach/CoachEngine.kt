package kr.stepdrive.core.coach

import kr.stepdrive.core.hazard.HazardIndex
import kr.stepdrive.core.model.Maneuver
import kr.stepdrive.core.model.ManeuverType
import kr.stepdrive.core.model.NavState
import kr.stepdrive.core.model.TurnSignal
import kr.stepdrive.core.model.VehicleSample
import kr.stepdrive.core.model.requiredSignal
import kr.stepdrive.core.model.freshAt
import kr.stepdrive.core.model.Gear
import kr.stepdrive.core.model.NavStatus
import kotlin.math.abs

/**
 * WARNING  – danger ahead (spoken immediately, interrupts).
 * TURN     – the turn-preparation announcement.
 * NOTICE   – reference notices about the driver's own operation (no score impact, never muted).
 * ANSWER   – replies to the driver's voice commands.
 * COACH    – short improvement tips.
 * PRAISE   – confirmation that the driver did it.
 */
enum class Priority { WARNING, DIRECTION_CHECK, TURN, NOTICE, ANSWER, COACH, PRAISE }

data class CoachEvent(val priority: Priority, val text: String, val tMs: Long)

/**
 * Live coaching while driving: announce → check the car actually did it → short feedback.
 * Rules: warnings first; one short sentence at a time; a minimum gap between non-warning utterances.
 *
 * [feedbackLevel] (3 = full, 2 = short, 1 = quiet) is the "fading feedback" from the proposal:
 *  3: everything, plus one short reason after a coaching line
 *  2: no praise, no reasons
 *  1: warnings, turn announcements and notices only
 */
class CoachEngine(
    private val hazards: HazardIndex,
    private val hazardWarnM: Double = 500.0,
    private val prepLeadSeconds: Double = 25.0,
    private val minGapMs: Long = 3_000,
    private val harshBrakeMps2: Double = -3.5,
    private val feedbackLevel: Int = 3,
    /** Lane-change notice: steering beyond this angle at or above [laneNoticeMinKmh] with the signal off. */
    private val laneNoticeDeg: Float = 90f,
    private val laneNoticeMinKmh: Float = 30f,
    /** Too-slow notice: this far under the limit for [slowHoldMs]. */
    private val slowUnderLimitKmh: Int = 20,
    private val slowHoldMs: Long = 20_000,
) {
    private var lastSpokenAt: Long? = null
    private var warnedHazardId: String? = null
    private var warnedNavCaution = false
    private var prepAnnouncedFor: String? = null
    private var chainAnnouncedFor: String? = null
    private var praisedFor: String? = null
    private var missedFor: String? = null
    private var lastSample: VehicleSample? = null
    private var lastBrakeCoachAt: Long? = null
    private var lastLaneNoticeAt: Long? = null
    private var slowSince: Long? = null
    private var lastSlowNoticeAt: Long? = null
    private var followUp: CoachEvent? = null
    private var previousManeuver: Maneuver? = null
    private var mismatchSince: Long? = null
    private var mismatchSpoken = false
    private var routeRevision: Long? = null
    private val pendingEvents = mutableMapOf<Priority, CoachEvent>()

    fun tick(sample: VehicleSample, navigation: NavState): List<CoachEvent> {
        val s = sample.freshAt()
        val nav = navigation.freshAt(s.tMs)
        val out = mutableListOf<CoachEvent>()
        hazardWarning(s, nav)?.let { out += it }
        maneuverCoaching(s, nav, out)
        laneChangeNotice(s, nav, out)
        tooSlowNotice(s, nav, out)
        harshBrakeCoaching(s, out)
        lastSample = s
        return arbitrate(out, s.tMs)
    }

    private fun hazardWarning(s: VehicleSample, nav: NavState): CoachEvent? {
        // Source 1: the navigation's own caution point (ACCIDENT_PRONE_AREA).
        nav.accidentProneAheadM?.let { d ->
            if (d in 1..hazardWarnM.toInt() && !warnedNavCaution) {
                warnedNavCaution = true
                return CoachEvent(Priority.WARNING, "곧 사고가 잦은 구간이에요. 속도를 줄이고 좌우를 살피세요", s.tMs)
            }
            if (d > hazardWarnM) warnedNavCaution = false
        }
        // Source 2: our hazard index built from public accident data.
        val loc = nav.location ?: return null
        val ahead = hazards.nearestAhead(loc, nav.headingDeg, maxM = hazardWarnM) ?: run { warnedHazardId = null; return null }
        if (warnedHazardId == ahead.hazard.id) return null
        warnedHazardId = ahead.hazard.id
        return CoachEvent(Priority.WARNING, "${ahead.distanceM.toInt() / 10 * 10}m 앞 사고가 잦은 교차로예요. 속도를 줄이세요", s.tMs)
    }

    private fun maneuverCoaching(s: VehicleSample, nav: NavState, out: MutableList<CoachEvent>) {
        val m = nav.next.takeIf { nav.status == NavStatus.ROUTE_DRIVE }
        val previous = previousManeuver
        if (routeRevision != nav.routeRevision || m == null || previous == null || m.type != previous.type ||
            m.description != previous.description || m.distanceM > previous.distanceM + 80) {
            prepAnnouncedFor = null
            chainAnnouncedFor = null
            praisedFor = null
            missedFor = null
            followUp = null
            pendingEvents.remove(Priority.TURN)
            pendingEvents.remove(Priority.COACH)
            pendingEvents.remove(Priority.PRAISE)
            resetMismatch()
        }
        previousManeuver = m
        routeRevision = nav.routeRevision
        if (m == null) return
        val need = m.type.requiredSignal ?: return
        val key = "${m.type}:${m.description}"
        val v = (s.speedKmh ?: 30f) / 3.6
        val prepDistance = (v * prepLeadSeconds).coerceIn(120.0, 600.0)

        // This compares the indicator with guidance; it does not infer the occupied lane.
        if (hasDirectionMismatch(s, nav, prepLeadSeconds)) {
            if (mismatchSince == null) mismatchSince = s.tMs
            pendingEvents.remove(Priority.PRAISE)
            if (s.tMs - mismatchSince!! >= 1_000) {
                praisedFor = null
                if (!mismatchSpoken) out += CoachEvent(Priority.DIRECTION_CHECK,
                    "안내는 ${need.label()}, 깜빡이는 ${s.turnSignal.label()}이에요. 확인하세요", s.tMs)
            }
        } else resetMismatch()
        // Do not speak a queued success after the input has changed or disappeared.
        if (s.turnSignal != need) pendingEvents.remove(Priority.PRAISE)

        if (m.distanceM <= prepDistance && prepAnnouncedFor != key) {
            prepAnnouncedFor = key
            out += CoachEvent(Priority.TURN, prepText(m.type, m.description), s.tMs)
            // Consecutive turns: tell the driver now that another turn follows right after this one.
            val following = nav.maneuvers.getOrNull(1)
            if (following != null && following.type.requiredSignal != null && following.distanceM - m.distanceM in 1.0..400.0 && chainAnnouncedFor != key) {
                chainAnnouncedFor = key
                val gap = ((following.distanceM - m.distanceM).toInt() / 50 * 50).coerceAtLeast(50)
                // Spoken as the next line, after the minimum gap, so the two sentences stay short.
                followUp = CoachEvent(Priority.TURN, "${m.type.shortLabel()} 뒤 ${gap}m에서 바로 ${following.type.shortLabel()}이에요", s.tMs)
            }
        }
        if (prepAnnouncedFor == key && s.turnSignal == need && praisedFor != key) {
            praisedFor = key
            out += CoachEvent(Priority.PRAISE, "좋아요, 깜빡이 확인했어요", s.tMs)
        }
        if (m.distanceM <= 30 && praisedFor != key && missedFor != key && s.turnSignal == TurnSignal.OFF) {
            missedFor = key
            out += CoachEvent(Priority.COACH, "다음엔 깜빡이를 미리 켜 볼까요", s.tMs)
            if (feedbackLevel >= 3) followUp = CoachEvent(Priority.COACH, "깜빡이는 뒤차에게 미리 알리는 신호예요", s.tMs)
        }
    }

    private fun resetMismatch() {
        mismatchSince = null
        mismatchSpoken = false
        pendingEvents.remove(Priority.DIRECTION_CHECK)
    }

    /** Turn announcement with the navigation's own phrase (e.g. "대왕판교IC 방면") when it is short enough. */
    private fun prepText(type: ManeuverType, description: String): String {
        val where = description.trim().takeIf { it.isNotEmpty() && it.length <= 12 && !it.contains("회전") && !it.contains("합류") }
        val base = when (type) {
            ManeuverType.LEFT -> "곧 좌회전이에요"
            ManeuverType.RIGHT -> "곧 우회전이에요"
            ManeuverType.MERGE_LEFT -> "곧 왼쪽으로 합류해요"
            ManeuverType.MERGE_RIGHT -> "곧 오른쪽으로 합류해요"
            ManeuverType.EXIT_LEFT -> "곧 왼쪽 출구예요"
            ManeuverType.EXIT_RIGHT -> "곧 오른쪽 출구예요"
            ManeuverType.EXIT -> "곧 출구예요"
            ManeuverType.UTURN -> "곧 유턴이에요"
            else -> "곧 회전이에요"
        }
        val action = when (type) {
            ManeuverType.MERGE_LEFT, ManeuverType.MERGE_RIGHT -> "깜빡이를 켜고 속도를 맞추세요"
            ManeuverType.UTURN -> "왼쪽 깜빡이를 켜세요"
            else -> "주변을 확인하고 미리 준비하세요"
        }
        val text = if (where != null) "$base, $where. $action" else "$base. $action"
        return if (text.length <= MAX_CHARS_WHILE_DRIVING) text else "$base. $action"
    }

    /** Reference notice (not scored): the wheel is turned a lot at speed while the signal is off and no turn is near. */
    private fun laneChangeNotice(s: VehicleSample, nav: NavState, out: MutableList<CoachEvent>) {
        val angle = s.steeringAngleDeg?.takeIf { it.isFinite() } ?: return
        val speed = s.speedKmh ?: return
        if (s.gear != Gear.DRIVE || speed < laneNoticeMinKmh || abs(angle) < laneNoticeDeg || s.turnSignal != TurnSignal.OFF) return
        val turnNear = nav.next?.let { it.type.requiredSignal != null && it.distanceM <= 200.0 } == true
        if (turnNear) return
        if (lastLaneNoticeAt?.let { s.tMs - it < 30_000 } == true) return
        lastLaneNoticeAt = s.tMs
        out += CoachEvent(Priority.NOTICE, "핸들이 많이 돌았는데 깜빡이가 꺼져 있어요", s.tMs)
    }

    /** Reference notice: well under the road's limit for a while on a road with a meaningful limit. */
    private fun tooSlowNotice(s: VehicleSample, nav: NavState, out: MutableList<CoachEvent>) {
        val limit = nav.speedLimitKmh ?: run { slowSince = null; return }
        val speed = s.speedKmh ?: run { slowSince = null; return }
        if (limit < 50 || speed < 5f) { slowSince = null; return }
        if (speed <= limit - slowUnderLimitKmh) {
            if (slowSince == null) slowSince = s.tMs
            if (s.tMs - slowSince!! >= slowHoldMs && (lastSlowNoticeAt?.let { s.tMs - it > 60_000 } != false)) {
                lastSlowNoticeAt = s.tMs; slowSince = null
                out += CoachEvent(Priority.NOTICE, "제한속도 ${limit} 도로예요. 흐름에 맞춰 조금 더 내 보세요", s.tMs)
            }
        } else slowSince = null
    }

    private fun harshBrakeCoaching(s: VehicleSample, out: MutableList<CoachEvent>) {
        val prev = lastSample ?: return
        val v0 = prev.speedKmh ?: return
        val v1 = s.speedKmh ?: return
        val dt = (s.speedTMs - prev.speedTMs) / 1000.0
        if (dt <= 0.0 || dt > 3.0) return
        val a = ((v1 - v0) / 3.6) / dt
        if (a <= harshBrakeMps2 && (lastBrakeCoachAt?.let { s.tMs - it > 20_000 } != false)) {
            lastBrakeCoachAt = s.tMs
            out += CoachEvent(Priority.COACH, "조금 더 일찍, 부드럽게 멈춰 볼까요", s.tMs)
            if (feedbackLevel >= 3) followUp = CoachEvent(Priority.COACH, "급제동은 뒤차가 부딪힐 수 있어요", s.tMs)
        }
    }

    /** Keep warnings; of the rest, speak at most one and only after the minimum gap; mute by feedback level. */
    private fun arbitrate(events: List<CoachEvent>, nowMs: Long): List<CoachEvent> {
        val allowed = events.filter { allowedAtLevel(it.priority) }
        val warnings = allowed.filter { it.priority == Priority.WARNING }
        allowed.filter { it.priority != Priority.WARNING }.forEach { pendingEvents[it.priority] = it }
        if (warnings.isNotEmpty()) {
            lastSpokenAt = nowMs
            followUp = null
            // Keep the turn announcement until the warning finishes and the minimum gap passes.
            pendingEvents.keys.removeAll { it != Priority.TURN }
            return warnings
        }
        if (lastSpokenAt?.let { nowMs - it < minGapMs } == true) return emptyList()
        val event = pendingEvents.values.minByOrNull { it.priority.ordinal }
            ?: followUp?.takeIf { allowedAtLevel(it.priority) }
            ?: return emptyList()
        pendingEvents.remove(event.priority)
        if (event.priority == Priority.DIRECTION_CHECK) mismatchSpoken = true
        if (event === followUp) followUp = null
        lastSpokenAt = nowMs
        return listOf(event.copy(tMs = nowMs))
    }

    private fun allowedAtLevel(p: Priority): Boolean = when (feedbackLevel.coerceIn(1, 3)) {
        3 -> true
        2 -> p != Priority.PRAISE
        else -> p == Priority.WARNING || p == Priority.DIRECTION_CHECK || p == Priority.TURN || p == Priority.NOTICE || p == Priority.ANSWER
    }

    companion object {
        /** Safety rule from the proposal: short sentences while moving. */
        const val MAX_CHARS_WHILE_DRIVING = 40
    }
}

internal fun TurnSignal.label() = when (this) { TurnSignal.LEFT -> "왼쪽"; TurnSignal.RIGHT -> "오른쪽"; TurnSignal.OFF, TurnSignal.UNKNOWN -> "" }

private fun ManeuverType.shortLabel() = when (this) {
    ManeuverType.LEFT -> "좌회전"; ManeuverType.RIGHT -> "우회전"
    ManeuverType.MERGE_LEFT, ManeuverType.MERGE_RIGHT -> "합류"
    ManeuverType.EXIT, ManeuverType.EXIT_LEFT, ManeuverType.EXIT_RIGHT -> "출구"; ManeuverType.UTURN -> "유턴"; else -> "회전"
}

/** Current, observable mismatch only. Direction of travel/lane occupancy cannot be determined here. */
fun hasDirectionMismatch(sample: VehicleSample, navigation: NavState, prepLeadSeconds: Double = 25.0): Boolean {
    val s = sample.freshAt()
    val nav = navigation.freshAt(s.tMs)
    if (s.gear != Gear.DRIVE || (s.speedKmh ?: 0f) <= 0.5f || nav.status != NavStatus.ROUTE_DRIVE) return false
    val m = nav.next ?: return false
    val expected = m.type.requiredSignal ?: return false
    val approachM = (s.speedKmh!! / 3.6 * prepLeadSeconds).coerceIn(120.0, 600.0)
    return m.distanceM in 0.0..approachM &&
        s.turnSignal in setOf(TurnSignal.LEFT, TurnSignal.RIGHT) && s.turnSignal != expected
}
