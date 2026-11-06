package kr.stepdrive.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kr.stepdrive.app.data.ProfileStore
import kr.stepdrive.app.location.NoLocationSource
import kr.stepdrive.app.navi.Destination
import kr.stepdrive.app.navi.NavSource
import kr.stepdrive.app.session.DriveSessionController
import kr.stepdrive.app.speech.NoVoiceCommandSource
import kr.stepdrive.app.speech.Speaker
import kr.stepdrive.app.vehicle.VehicleSignalSource
import kr.stepdrive.core.hazard.HazardIndex
import kr.stepdrive.core.model.*
import org.junit.Test
import kotlin.test.*

class SessionAndStoreTest {
    @Test(timeout = 20_000) fun concurrentCreatesTripsAndTouchesPreserveAllRecords() = runBlocking {
        withStore { store, _ ->
            val drivers = coroutineScope { (1..4).map { async(Dispatchers.Default) { store.create("운전자$it", "1234") } }.awaitAll() }
            assertEquals(4, store.profiles.first().size)
            val stale = drivers.first()
            coroutineScope {
                (1..12).map { index -> launch(Dispatchers.Default) { store.applyTrip(stale, trip("trip-$index")) } }.joinAll()
                listOf(launch { store.touch(stale) }, launch { store.applyTrip(stale, trip("last")) }).joinAll()
            }
            store.applyTrip(stale, trip("last"))
            val saved = store.profiles.first().single { it.id == stale.id }
            assertEquals(13, saved.trips.size)
            assertEquals(1_300, saved.skill.xp)
        }
    }

    @Test(timeout = 20_000) fun deletedProfileIsNotRecreatedByAStaleTrip() = runBlocking {
        withStore { store, _ ->
            val driver = store.create("운전자", "1234")
            store.delete(driver.id)
            assertFailsWith<IllegalStateException> { store.applyTrip(driver, trip("late")) }
            assertTrue(store.profiles.first().isEmpty())
        }
    }

    @Test(timeout = 20_000) fun parkSignalCompletesParkingThroughTheSessionController() = runBlocking {
        withStore { store, scope ->
            val driver = store.create("운전자", "1234")
            val vehicle = FakeVehicle()
            val navigation = object : NavSource {
                override val nav = MutableStateFlow(NavState(NavStatus.FREE_DRIVE))
                override fun start() = Unit
                override fun stop() = Unit
                override fun requestRoute(destination: Destination, waypoints: List<Destination>) = true
            }
            val speaker = object : Speaker {
                override fun speak(text: String, urgent: Boolean) = Unit
                override fun stop() = Unit
                override fun release() = Unit
            }
            val controller = DriveSessionController(scope, vehicle, navigation, NoLocationSource(), speaker, NoVoiceCommandSource(), store)
            controller.start(driver, Tier.CITY, HazardIndex(emptyList()))
            suspend fun feed(t: Long, speed: Float, gear: Gear, steering: Float = 0f) {
                vehicle.samples.value = VehicleSample(t, speed, gear, TurnSignal.OFF, steeringAngleDeg = steering, odometerKm = 100f + t / 1_000_000f)
                controller.state.first { it.sample.tMs == t }
            }
            feed(1_000, 0f, Gear.REVERSE)
            feed(2_000, 2f, Gear.REVERSE)
            feed(3_000, 2f, Gear.REVERSE, 400f)
            feed(4_000, 2f, Gear.REVERSE, 100f)
            feed(5_000, 0f, Gear.REVERSE, 30f)
            feed(6_600, 0f, Gear.REVERSE, 30f)
            feed(7_000, 0f, Gear.PARK, 30f)
            val finished = controller.state.first { it.tripScore != null }
            assertEquals(1, finished.tripScore!!.events.count { it.kind == TripEvent.Kind.PARKING_DONE })
            assertEquals(1, store.profiles.first().single().trips.size)
            controller.stop()
        }
    }

    private suspend fun withStore(block: suspend (ProfileStore, CoroutineScope) -> Unit) {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        try { block(ProfileStore(MemoryPreferences()), scope) }
        finally { job.cancelAndJoin() }
    }

    /** Models DataStore's serialized transaction boundary without requiring an Android filesystem. */
    private class MemoryPreferences : DataStore<Preferences> {
        private val mutex = Mutex()
        private val values = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = values

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock {
                yield() // Let competing callers reach the transaction boundary.
                transform(values.value).also { values.value = it }
            }
    }

    private class FakeVehicle : VehicleSignalSource {
        override val samples = MutableStateFlow(VehicleSample(0, 0f, Gear.PARK))
        override fun start() = Unit
        override fun stop() = Unit
    }

    private fun trip(id: String) = TripScore(id, 1_000, 2_000, 5.0, Tier.CITY, 100, 100, 1.0, 0.0, 0.0, 0.0, null, emptyList(), "", null)
}
