package kr.stepdrive.app.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kr.stepdrive.core.model.TripScore

@Serializable
data class SessionCheckpoint(val profileId: String, val trip: TripScore, val writtenAtMs: Long)

/** Atomic, bounded checkpoint. Replayed by trip ID so a crash after final-save cannot duplicate a trip. */
class SessionCheckpointStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private var activeTrip: String? = null
    @Synchronized fun begin(tripId: String) { activeTrip = tripId }
    @Synchronized fun write(checkpoint: SessionCheckpoint) {
        if (activeTrip != checkpoint.trip.tripId) return
        val bytes = json.encodeToString(checkpoint).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 4_000_000) { "Checkpoint too large" }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.outputStream().use { it.write(bytes); it.fd.sync() }
        try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
    @Synchronized fun read(): SessionCheckpoint? {
        if (!file.exists()) return null
        check(file.length() <= 4_000_000) { "Checkpoint too large" }
        return json.decodeFromString(file.readText(Charsets.UTF_8))
    }
    @Synchronized fun clear(tripId: String?) {
        if (activeTrip != null && tripId != activeTrip) return
        if (file.exists()) {
            val saved = read()
            if (saved?.trip?.tripId != tripId) return
            check(file.delete()) { "Checkpoint cleanup failed" }
        }
        activeTrip = null
    }
    suspend fun recover(store: ProfileStore, now: Long = System.currentTimeMillis()): String? {
        val saved = try { read() } catch (_: Exception) {
            synchronized(this) {
                if (file.exists()) Files.move(file.toPath(), File(file.parentFile, "${file.name}.corrupt-$now").toPath())
            }
            return "중간 기록을 읽지 못해 별도 파일로 보관했어요. 정차 후 새 연습을 시작하세요"
        } ?: return null
        val driver = store.profiles.first().firstOrNull { it.id == saved.profileId }
        if (driver == null || now - saved.writtenAtMs !in 0..24 * 60 * 60 * 1_000L) {
            clear(saved.trip.tripId)
            return "지난 연습이 중단됐어요. 정차 후 새 연습을 시작하세요"
        }
        // applyTrip already deduplicates IDs and refuses deleted profiles.
        store.applyTrip(driver, saved.trip.copy(provisional = true, xp = 0, nextPractice = null))
        clear(saved.trip.tripId)
        return "중단된 연습 기록을 복구했어요. 누적 점수에는 반영하지 않았어요"
    }
}
