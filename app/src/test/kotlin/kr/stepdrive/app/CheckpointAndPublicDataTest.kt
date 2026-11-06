package kr.stepdrive.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kr.stepdrive.app.data.*
import kr.stepdrive.app.hazard.*
import kr.stepdrive.core.model.*
import java.io.File
import java.nio.file.Files
import org.junit.Test
import kotlin.test.*

internal class FeaturePreferences : DataStore<Preferences> {
    private val values = MutableStateFlow(emptyPreferences())
    private val lock = Mutex()
    override val data: Flow<Preferences> = values
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock {
        transform(values.value).also { values.value = it }
    }
}

class CheckpointAndPublicDataTest {
    private fun trip(id: String) = TripScore(id, 1_000, 2_000, 5.0, Tier.CITY, 100, 100, 1.0, 0.0, 0.0, 0.0, null, emptyList(), "", null)
    private suspend fun withFile(test: suspend (File) -> Unit) {
        val dir = Files.createTempDirectory("checkpoint-test-").toFile()
        try { test(File(dir, "checkpoint.json")) } finally { dir.deleteRecursively() }
    }
    @Test fun newStoreInstanceRecoversInterruptedRecordWithoutXpAndOnlyOnce() = runBlocking {
        withFile { file ->
            val profiles = ProfileStore(FeaturePreferences()); val driver = profiles.create("검증", "1234")
            val first = SessionCheckpointStore(file); first.begin("a"); first.write(SessionCheckpoint(driver.id, trip("a"), 2_000))
            val restarted = SessionCheckpointStore(file)
            assertNotNull(restarted.recover(profiles, 3_000)); assertNull(restarted.recover(profiles, 4_000))
            val saved = profiles.profiles.first().single()
            assertEquals(1, saved.trips.size); assertTrue(saved.trips.single().provisional); assertEquals(0, saved.skill.xp)
            assertFalse(file.exists())
        }
    }
    @Test fun crashAfterFinalSaveDoesNotDowngradeOrDuplicateFinishedTrip() = runBlocking {
        withFile { file ->
            val profiles = ProfileStore(FeaturePreferences()); val driver = profiles.create("검증", "1234")
            profiles.applyTrip(driver, trip("a"))
            val checkpoints = SessionCheckpointStore(file); checkpoints.begin("a"); checkpoints.write(SessionCheckpoint(driver.id, trip("a"), 2_000))
            checkpoints.recover(profiles, 3_000)
            val saved = profiles.profiles.first().single()
            assertEquals(1, saved.trips.size); assertFalse(saved.trips.single().provisional); assertEquals(100, saved.skill.xp)
        }
    }
    @Test fun previousSessionWriteAndCleanupCannotEraseNewSession() = runBlocking {
        withFile { file ->
            val cp = SessionCheckpointStore(file); cp.begin("a"); cp.begin("b")
            cp.write(SessionCheckpoint("driver", trip("b"), 2_000))
            cp.write(SessionCheckpoint("driver", trip("a"), 3_000)); cp.clear("a")
            assertEquals("b", cp.read()!!.trip.tripId)
        }
    }
    @Test fun deletedDriverCannotBeResurrectedByRecovery() = runBlocking {
        withFile { file ->
            val store = ProfileStore(FeaturePreferences()); val cp = SessionCheckpointStore(file)
            cp.begin("a"); cp.write(SessionCheckpoint("deleted", trip("a"), 2_000))
            cp.recover(store, 3_000)
            assertTrue(store.profiles.first().isEmpty()); assertFalse(file.exists())
        }
    }
    @Test fun malformedCheckpointIsPreservedSeparatelyBeforeNextSession() = runBlocking {
        withFile { file ->
            file.writeText("broken-json")
            val cp = SessionCheckpointStore(file)
            assertNotNull(cp.recover(ProfileStore(FeaturePreferences()), 3_000))
            cp.begin("b"); cp.write(SessionCheckpoint("driver", trip("b"), 4_000))
            assertEquals("broken-json", File(file.parentFile, "checkpoint.json.corrupt-3000").readText())
            assertEquals("b", cp.read()!!.trip.tripId)
        }
    }
    private fun page(id: String, total: Int = 2) = """{"resultCode":"00","totalCount":$total,"items":{"item":{"afos_fid":"$id","la_crd":37.5,"lo_crd":127.0}}}"""
    @Test fun allPagesAreRequiredAndRawOrEncodedKeysAreEncodedExactlyOnce() {
        for (key in listOf("a+b/c=", "a%2Bb%2Fc%3D")) {
            val urls = mutableListOf<String>()
            val client = KoRoadClient { url -> urls += url; page("p${urls.size}") }
            assertEquals(2, client.load(key, 2024, "11680").size)
            assertTrue(urls.first().contains("serviceKey=a%2Bb%2Fc%3D&"))
            assertTrue(urls.first().contains("siDo=11&guGun=680&"))
            assertTrue(urls.last().endsWith("pageNo=2"))
        }
    }
    @Test fun duplicatePartialAndChangingTotalsFailInsteadOfPublishingPartialData() {
        assertFails { KoRoadClient { page("duplicate") }.load("test", 2024, "11680") }
        var n = 0
        assertFails { KoRoadClient { n++; if (n == 1) page("a") else """{"resultCode":"00","totalCount":2,"items":[]}""" }.load("test", 2024, "11680") }
        n = 0
        assertFails { KoRoadClient { n++; page("p$n", n + 1) }.load("test", 2024, "11680") }
    }
    @Test fun malformedInputIsRejectedBeforeNetworkAccess() {
        var calls = 0; val client = KoRoadClient { calls++; page("a", 1) }
        assertFails { client.load("", 2024, "11680") }
        assertFails { client.load("test", 2024, "11&evil=1") }
        assertFails { client.load("test", 1800, "11680") }
        assertEquals(0, calls)
    }
}
