package kr.stepdrive.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kr.stepdrive.core.model.TripScore
import kr.stepdrive.core.profile.DriverProfile
import kr.stepdrive.core.profile.PinHasher
import kr.stepdrive.core.profile.ProfilePolicy
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("stepdrive")

/** Stores the driver's records locally. All read-modify-write operations share a DataStore transaction. */
class ProfileStore(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.dataStore)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = stringPreferencesKey("profiles")

    val profiles: Flow<List<DriverProfile>> = dataStore.data.map { prefs ->
        // Corrupt data must be reported rather than displayed as a brand new empty account.
        prefs[key]?.let { json.decodeFromString<List<DriverProfile>>(it) } ?: emptyList()
    }

    suspend fun create(name: String, pin: String, nowMs: Long = System.currentTimeMillis()): DriverProfile {
        val salt = PinHasher.newSalt()
        val profile = DriverProfile(
            id = UUID.randomUUID().toString(), name = name.trim(), pinHash = PinHasher.hash(pin, salt), salt = salt,
            createdAtMs = nowMs, lastUsedAtMs = nowMs,
        )
        transformProfiles { it + profile }
        return profile
    }

    fun verifyPin(profile: DriverProfile, pin: String): Boolean = PinHasher.verify(pin, profile.salt, profile.pinHash)

    suspend fun touch(profile: DriverProfile, nowMs: Long = System.currentTimeMillis()): DriverProfile =
        updateCurrent(profile.id) { it.copy(lastUsedAtMs = nowMs) }

    suspend fun applyTrip(profile: DriverProfile, trip: TripScore, nowMs: Long = System.currentTimeMillis()): DriverProfile =
        updateCurrent(profile.id) { current ->
            if (current.trips.any { it.tripId == trip.tripId }) current else current.withTrip(trip, nowMs)
        }

    suspend fun delete(profileId: String) = transformProfiles { list -> list.filterNot { it.id == profileId } }

    suspend fun deleteAll() = transformProfiles { emptyList() }

    suspend fun purgeExpired(nowMs: Long = System.currentTimeMillis()): Int {
        var removed = 0
        transformProfiles { list ->
            list.filterNot { ProfilePolicy.isExpired(it, nowMs) }.also { removed = list.size - it.size }
        }
        return removed
    }

    private suspend fun updateCurrent(id: String, transform: (DriverProfile) -> DriverProfile): DriverProfile {
        var updated: DriverProfile? = null
        transformProfiles { list ->
            val current = list.firstOrNull { it.id == id } ?: error("운전자 프로필이 삭제되어 기록을 저장할 수 없어요")
            val next = transform(current)
            updated = next
            list.map { if (it.id == id) next else it }
        }
        return checkNotNull(updated)
    }

    private suspend fun transformProfiles(transform: (List<DriverProfile>) -> List<DriverProfile>) {
        dataStore.edit { prefs ->
            // A decoding failure aborts the transaction and preserves the existing data.
            val current = prefs[key]?.let { json.decodeFromString<List<DriverProfile>>(it) } ?: emptyList()
            prefs[key] = json.encodeToString(transform(current))
        }
    }
}
