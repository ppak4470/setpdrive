package kr.stepdrive.core.profile

import kotlinx.serialization.Serializable
import kr.stepdrive.core.model.PracticePoint
import kr.stepdrive.core.model.TripScore
import kr.stepdrive.core.score.SkillState
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A driver inside the car, without any account or external app. Protected by a PIN so another driver
 * — or the next owner of a used car — cannot read the records.
 */
@Serializable
data class DriverProfile(
    val id: String,
    val name: String,
    val pinHash: String,
    val salt: String,
    val createdAtMs: Long,
    val lastUsedAtMs: Long,
    val skill: SkillState = SkillState(),
    val trips: List<TripScore> = emptyList(),
    val openPractice: PracticePoint? = null,
) {
    fun withTrip(trip: TripScore, nowMs: Long): DriverProfile = copy(
        skill = skill.apply(trip),
        trips = (trips + trip).takeLast(50),
        openPractice = trip.nextPractice ?: openPractice?.takeUnless {
            trip.reviewedPractice?.id == it.id && trip.reviewedPractice?.verified == true
        },
        lastUsedAtMs = nowMs,
    )
}

object PinHasher {
    private const val ITERATIONS = 20_000

    fun newSalt(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()

    fun hash(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        var digest = md.digest((salt + pin).toByteArray())
        repeat(ITERATIONS - 1) { digest = md.digest(digest + salt.toByteArray()) }
        return digest.toHex()
    }

    fun verify(pin: String, salt: String, expectedHash: String): Boolean =
        MessageDigest.isEqual(hash(pin, salt).toByteArray(), expectedHash.toByteArray())

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}

/** Retention rule: profiles unused for [maxIdleDays] are deleted automatically (used-car hand-over protection). */
object ProfilePolicy {
    const val DEFAULT_MAX_IDLE_DAYS = 180
    fun isExpired(profile: DriverProfile, nowMs: Long, maxIdleDays: Int = DEFAULT_MAX_IDLE_DAYS): Boolean =
        nowMs - profile.lastUsedAtMs > maxIdleDays * 24L * 60 * 60 * 1000
}
