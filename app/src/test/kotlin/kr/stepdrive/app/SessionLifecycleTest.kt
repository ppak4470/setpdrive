package kr.stepdrive.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kr.stepdrive.app.data.ProfileStore
import kr.stepdrive.app.location.NoLocationSource
import kr.stepdrive.app.navi.*
import kr.stepdrive.app.session.*
import kr.stepdrive.app.speech.*
import kr.stepdrive.app.vehicle.VehicleSignalSource
import kr.stepdrive.core.hazard.HazardIndex
import kr.stepdrive.core.model.*
import kr.stepdrive.core.parking.ParkingStep
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import kotlin.test.*

class SessionLifecycleTest {
    @Test(timeout = 30_000) fun failedTripSaveIsVisibleAndANewSessionCanStillSave() = runBlocking {
        withHarness { h ->
            val p = h.store.create("test", "1234")
            h.controller.start(p, Tier.CITY, h.hazards)
            h.feed(1_000, 20f, Gear.DRIVE)
            h.data.fail = true
            h.feed(2_000, 0f, Gear.PARK)
            val failed = withTimeout(3_000) { h.controller.state.first { it.tripScore != null } }
            assertNotNull(failed.saveError)
            assertTrue(h.store.profiles.first().single().trips.isEmpty())
            h.data.fail = false
            h.controller.start(p, Tier.CITY, h.hazards)
            h.feed(3_000, 20f, Gear.DRIVE)
            h.feed(4_000, 0f, Gear.PARK)
            withTimeout(3_000) { h.controller.state.first { it.tripScore != null } }
            assertEquals(1, h.store.profiles.first().single().trips.size)
        }
    }

    @Test(timeout = 30_000) fun repeatedSessionsKeepTheTwoDriversSeparate() = runBlocking {
        withHarness { h ->
            val drivers = listOf(h.store.create("one", "1234"), h.store.create("two", "5678"))
            repeat(12) { i ->
                h.controller.start(drivers[i % 2], Tier.CITY, h.hazards)
                h.feed(i * 3_000L + 1_000, 20f, Gear.DRIVE)
                h.feed(i * 3_000L + 2_000, 0f, Gear.PARK)
                val report = withTimeout(3_000) { h.controller.state.first { it.tripScore != null } }
                assertEquals(drivers[i % 2].id, report.profile!!.id)
                h.controller.stop()
                assertEquals(Phase.IDLE, h.controller.state.value.phase)
            }
            assertEquals(listOf(6, 6), h.store.profiles.first().map { it.trips.size })
        }
    }

    @Test(timeout = 30_000) fun parkWithUnknownSpeedDoesNotFinishATrip() = runBlocking {
        withHarness { h ->
            val p = h.store.create("unknown", "1234")
            h.controller.start(p, Tier.CITY, h.hazards)
            h.feed(1_000, 30f, Gear.DRIVE)
            h.feed(2_000, null, Gear.PARK)
            assertNull(h.controller.state.value.tripScore)
            assertTrue(h.store.profiles.first().single().trips.isEmpty())
            h.feed(3_000, 0f, Gear.PARK)
            withTimeout(3_000) { h.controller.state.first { it.tripScore != null } }
            assertEquals(1, h.store.profiles.first().single().trips.size)
        }
    }

    @Test(timeout = 30_000) fun cancellingASuspendedSaveDoesNotOverwriteTheNextSession() = runBlocking {
        withHarness { h ->
            val first = h.store.create("first", "1234")
            val second = h.store.create("second", "1234")
            h.controller.start(first, Tier.CITY, h.hazards)
            h.feed(1_000, 30f, Gear.DRIVE)
            h.data.block = true
            h.feed(2_000, 0f, Gear.PARK)
            withTimeout(3_000) { h.data.entered.await() }
            h.controller.start(second, Tier.CITY, h.hazards)
            h.data.block = false
            h.feed(3_000, 20f, Gear.DRIVE)
            assertEquals(second.id, h.controller.state.value.profile!!.id)
            assertEquals(Phase.DRIVING, h.controller.state.value.phase)
            assertNull(h.controller.state.value.tripScore)
        }
    }

    private suspend fun withHarness(block: suspend (Harness) -> Unit) {
        val dir = Files.createTempDirectory("stepdrive-session-").toFile()
        val file = dir.resolve("test.preferences_pb")
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val data = FaultStore(PreferenceDataStoreFactory.create(scope = scope) { file })
        val h = Harness(scope, data)
        try { block(h) } finally { h.controller.stop(); job.cancelAndJoin(); file.delete(); dir.delete() }
    }

    private class FaultStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        @Volatile var fail = false
        @Volatile var block = false
        val entered = CompletableDeferred<Unit>()
        override val data get() = delegate.data
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (fail) throw IOException("injected write failure")
            if (block) { entered.complete(Unit); awaitCancellation() }
            return delegate.updateData(transform)
        }
    }
    private class Harness(scope: CoroutineScope, val data: FaultStore) {
        val store = ProfileStore(data)
        val hazards = HazardIndex(emptyList())
        private val sampleStream = MutableStateFlow(VehicleSample(0, 0f, Gear.PARK))
        val vehicle = object : VehicleSignalSource {
            override val samples = sampleStream
            override fun start() = Unit
            override fun stop() = Unit
        }
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
        suspend fun feed(t: Long, speed: Float?, gear: Gear) {
            sampleStream.value = VehicleSample(t, speed, gear, TurnSignal.OFF, odometerKm = 10f + t / 1_000_000f)
            withTimeout(3_000) { controller.state.first { it.sample.tMs == t } }
        }
    }
}
