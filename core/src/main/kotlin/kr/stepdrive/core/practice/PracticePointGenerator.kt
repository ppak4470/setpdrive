package kr.stepdrive.core.practice

import kr.stepdrive.core.hazard.Geo
import kr.stepdrive.core.model.PracticePoint
import kr.stepdrive.core.model.TripEvent
import kr.stepdrive.core.model.TripScore

/**
 * Builds exactly one, concrete practice point from a finished trip:
 * what to do, where, by which criterion, and how it is verified on the next drive.
 */
class PracticePointGenerator {

    fun generate(trip: TripScore): PracticePoint? {
        // Pick the weakest item; one point per trip keeps the message clear.
        val candidates = listOfNotNull(
            trip.events.firstOrNull { it.kind == TripEvent.Kind.SIGNAL_MISSED }?.let { e ->
                PracticePoint(
                    id = "${trip.tripId}-signal", kind = e.kind,
                    title = "${placeOf(e)} ${e.detail.substringBefore(" 전")} 전에 깜빡이를 미리 켜기",
                    where = e.location, placeName = placeOf(e),
                    criterion = "회전 30m 전까지 깜빡이 켜기 · 다음 주행에서 이 지점 통과 시 자동 확인",
                    createdAtMs = trip.endedAtMs,
                )
            },
            trip.events.firstOrNull { it.kind == TripEvent.Kind.HAZARD_FAST }?.let { e ->
                PracticePoint(
                    id = "${trip.tripId}-hazard", kind = e.kind,
                    title = "${e.placeName} 진입 전에 속도 줄이기",
                    where = e.location, placeName = e.placeName,
                    criterion = "사고다발 구간 진입 속도 30km/h 이하 · 다음 주행에서 자동 확인",
                    createdAtMs = trip.endedAtMs,
                )
            },
            trip.events.firstOrNull { it.kind == TripEvent.Kind.HARSH_BRAKE }?.let { e ->
                PracticePoint(
                    id = "${trip.tripId}-brake", kind = e.kind,
                    title = "${placeOf(e)} 부근에서 일찍, 부드럽게 멈추기",
                    where = e.location, placeName = placeOf(e),
                    criterion = "감속 3.5 m/s² 이하 유지 · 다음 주행에서 이 지점 150m 안 급제동 0회",
                    createdAtMs = trip.endedAtMs,
                )
            },
            trip.events.firstOrNull { it.kind == TripEvent.Kind.HARSH_ACCEL }?.let { e ->
                PracticePoint(
                    id = "${trip.tripId}-accel", kind = e.kind,
                    title = "${placeOf(e)} 출발을 천천히",
                    where = e.location, placeName = placeOf(e),
                    criterion = "출발 가속 3.0 m/s² 이하 · 다음 주행에서 이 지점 150m 안 급가속 0회",
                    createdAtMs = trip.endedAtMs,
                )
            },
            trip.events.firstOrNull { it.kind == TripEvent.Kind.SPEEDING }?.let { e ->
                PracticePoint(
                    id = "${trip.tripId}-speed", kind = e.kind,
                    title = "${placeOf(e)} 제한속도 지키기",
                    where = e.location, placeName = placeOf(e),
                    criterion = "제한속도 +10km/h 이내 · 다음 주행에서 이 구간 과속 0회",
                    createdAtMs = trip.endedAtMs,
                )
            },
        )
        return candidates.firstOrNull()
    }

    private fun placeOf(e: TripEvent) = e.placeName.ifBlank { "지난번 표시한 지점" }

    /**
     * Did the next trip clear the practice point? Success means no event of the same kind within [radiusM]
     * of the practice location. Returns null when the trip did not pass near the point.
     */
    fun verify(point: PracticePoint, nextTrip: TripScore, radiusM: Double = 150.0, passedNear: Boolean? = null): Boolean? {
        val where = point.where
        val near = passedNear ?: (where != null && nextTrip.events.any { ev ->
            ev.location != null && Geo.distanceM(ev.location, where) <= radiusM
        })
        if (!near) return null
        val failedAgain = nextTrip.events.any { ev ->
            ev.kind == point.kind && ev.location != null && where != null && Geo.distanceM(ev.location, where) <= radiusM
        }
        return !failedAgain
    }
}
