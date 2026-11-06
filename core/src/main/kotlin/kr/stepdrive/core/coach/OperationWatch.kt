package kr.stepdrive.core.coach

import kr.stepdrive.core.model.Gear
import kr.stepdrive.core.model.VehicleSample
import kr.stepdrive.core.model.freshAt
import kotlin.math.abs

/**
 * Reference notices about gear handling while parking (from the proposal's "잘못된 조작 경고"):
 *  - switching between R and D while the car is still moving
 *  - switching gear without the brake pressed (only when the brake signal is available)
 *  - leaving the car in D/R with the brake released after it has come to a stop at the end of parking
 * These never affect the score. Separate shifting incidents can warn again after a short cooldown.
 */
class OperationWatch(
    private val movingKmh: Float = 2f,
    private val noParkHoldMs: Long = 4_000,
) {
    data class Notice(val kind: Kind, val text: String, val reason: String)
    enum class Kind { SHIFT_WHILE_MOVING, SHIFT_WITHOUT_BRAKE, NO_PARK_AFTER_STOP }

    private var prev: VehicleSample? = null
    private var idleSince: Long? = null
    private var idleNoticeSent = false
    private val lastNoticeAt = mutableMapOf<Kind, Long>()

    fun reset() { prev = null; idleSince = null; idleNoticeSent = false; lastNoticeAt.clear() }

    /** [parkingFinished] is true once the parking checklist has reached its final check. */
    fun tick(sample: VehicleSample, parkingFinished: Boolean): Notice? {
        val s = sample.freshAt()
        val p = prev
        prev = s
        val speed = s.speedKmh?.let { abs(it) }
        if (p != null && p.gear != s.gear && s.gear != Gear.UNKNOWN && p.gear != Gear.UNKNOWN) {
            val drivingGears = setOf(Gear.DRIVE, Gear.REVERSE)
            if (p.gear in drivingGears && s.gear in drivingGears && speed != null && speed > movingKmh)
                return notice(Kind.SHIFT_WHILE_MOVING, s.tMs, "완전히 멈춘 뒤 기어를 바꾸세요", "움직이는 중에 R과 D를 바꾸면 변속기에 무리가 가고 차가 튈 수 있어요")
            if (s.brakePressed == false && s.gear in drivingGears)
                return notice(Kind.SHIFT_WITHOUT_BRAKE, s.tMs, "브레이크를 밟고 기어를 바꾸세요", "브레이크 없이 기어를 바꾸면 차가 갑자기 움직일 수 있어요")
        }
        if (parkingFinished && s.gear in setOf(Gear.DRIVE, Gear.REVERSE) && speed != null && speed < 0.5f && s.brakePressed == false) {
            if (idleSince == null) idleSince = s.tMs
            if (!idleNoticeSent && s.tMs - idleSince!! >= noParkHoldMs) {
                idleNoticeSent = true
                return notice(Kind.NO_PARK_AFTER_STOP, s.tMs, "브레이크를 밟고 정지한 뒤 P에 두세요", "P 없이 브레이크를 떼면 차가 밀릴 수 있어요")
            }
        } else { idleSince = null; idleNoticeSent = false }
        return null
    }

    private fun notice(kind: Kind, nowMs: Long, text: String, reason: String): Notice? {
        if (lastNoticeAt[kind]?.let { nowMs - it < 4_000 } == true) return null
        lastNoticeAt[kind] = nowMs
        return Notice(kind, text, reason)
    }
}
