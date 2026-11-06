package kr.stepdrive.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kr.stepdrive.app.data.ProfileStore
import kr.stepdrive.core.model.*
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class FileProfileStoreTest {
    @Test(timeout = 30_000) fun concurrentFileWritesSurviveReopeningAndIgnoreDuplicates() = runBlocking {
        val dir = Files.createTempDirectory("stepdrive-store-").toFile()
        val file = dir.resolve("test.preferences_pb")
        var job = SupervisorJob()
        try {
            var store = ProfileStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file })
            val driver = store.create("file-test", "1234")
            coroutineScope { (1..20).map { n -> async { store.applyTrip(driver, trip("trip-$n")) } }.awaitAll() }
            store.applyTrip(driver, trip("trip-20"))
            job.cancelAndJoin()
            job = SupervisorJob()
            store = ProfileStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file })
            val restored = store.profiles.first().single()
            assertEquals(20, restored.trips.size)
            assertEquals(2_000, restored.skill.xp)
            assertTrue(store.verifyPin(restored, "1234"))
            assertFalse(store.verifyPin(restored, "4321"))
            store.delete(restored.id)
            assertFailsWith<IllegalStateException> { store.applyTrip(driver, trip("late")) }
            assertTrue(store.profiles.first().isEmpty())
        } finally { job.cancelAndJoin(); file.delete(); dir.delete() }
    }

    @Test(timeout = 30_000) fun malformedProfileDataIsReportedAndPreserved() = runBlocking {
        val dir = Files.createTempDirectory("stepdrive-corrupt-").toFile()
        val file = dir.resolve("test.preferences_pb")
        val job = SupervisorJob()
        try {
            val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }
            val key = stringPreferencesKey("profiles")
            data.edit { it[key] = "broken-json" }
            val store = ProfileStore(data)
            assertFails { store.profiles.first() }
            assertFails { store.create("must-not-replace", "1234") }
            assertEquals("broken-json", data.data.first()[key])
        } finally { job.cancelAndJoin(); file.delete(); dir.delete() }
    }

    @Test(timeout = 30_000) fun expiryPurgesOnlyOldProfilesInTheRealFile() = runBlocking {
        val dir = Files.createTempDirectory("stepdrive-expiry-").toFile()
        val file = dir.resolve("test.preferences_pb")
        val job = SupervisorJob()
        try {
            val store = ProfileStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file })
            val day = 86_400_000L
            store.create("expired", "1234", 0)
            val recent = store.create("recent", "1234", 180 * day)
            assertEquals(1, store.purgeExpired(181 * day))
            assertEquals(listOf(recent.id), store.profiles.first().map { it.id })
        } finally { job.cancelAndJoin(); file.delete(); dir.delete() }
    }

    private fun trip(id: String) = TripScore(id, 1_000, 2_000, 5.0, Tier.CITY, 100, 100,
        1.0, 0.0, 0.0, 0.0, null, emptyList(), "", null)
}
