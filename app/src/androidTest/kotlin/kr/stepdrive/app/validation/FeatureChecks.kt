package kr.stepdrive.app.validation

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kr.stepdrive.app.MainActivity
import kr.stepdrive.app.di.AppContainer
import kr.stepdrive.app.data.*
import kr.stepdrive.app.hazard.*
import kr.stepdrive.app.navi.*
import kr.stepdrive.app.location.NoLocationSource
import kr.stepdrive.app.safety.PerceptionSource
import kr.stepdrive.app.session.*
import kr.stepdrive.app.speech.*
import kr.stepdrive.app.ui.MainViewModel
import kr.stepdrive.core.hazard.HazardIndex
import kr.stepdrive.core.model.*
import kr.stepdrive.core.safety.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject

/** Emulator vehicle input is real SDK input; perception/public API fixtures are explicitly synthetic. */
internal class FeatureChecks(private val context: Context, private val c: AppContainer, private val instrumentation: Instrumentation) {
    private val live = File(context.cacheDir, "validation-mistakes-live.json")
    private val marker = File(context.cacheDir, "validation-recovery-marker.json")
    private var requestId = 0
    private suspend fun input(name: String, gear: Gear = Gear.PARK, speed: Int = 0) {
        live.writeText(JSONObject().put("stage", "input").put("id", ++requestId).put("name", name)
            .put("gear", gear.name).put("speed", speed).put("signal", "OFF").put("brake", 30).put("steering", 0).toString())
        withTimeout(15_000) { c.vehicleSource.samples.first {
            it.gear == gear && it.speedKmh?.let { v -> kotlin.math.abs(v - speed) < 0.7f } == true &&
                System.currentTimeMillis() - it.speedTMs in 0..3_000
        } }
    }
    suspend fun run(test: suspend (String, suspend () -> Unit) -> Unit) {
        val profilesBefore = c.profileStore.profiles.first()
        test("real SDK Drive locks data settings and Park unlocks them") {
            input("settings parked")
            withTimeout(15_000) { c.recoveryReady.first { it }; c.uxRestrictions.restricted.first { !it } }
            val owner = ViewModelStore()
            val vm = withContext(Dispatchers.Main) { ViewModelProvider(owner, MainViewModel.factory(c))[MainViewModel::class.java] }
            try {
                withTimeout(10_000) { vm.ui.first { !it.restricted } }
                withContext(Dispatchers.Main) { vm.showDataSettings() }
                withTimeout(5_000) { vm.ui.first { it.dataSettings } }
                input("settings moving", Gear.DRIVE, 15)
                withTimeout(10_000) { vm.ui.first { it.restricted && !it.dataSettings } }
                withContext(Dispatchers.Main) { vm.showDataSettings() }
                check(!vm.ui.value.dataSettings)
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                delay(1_500)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.cacheDir, "validation-moving-ui.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                input("settings parked again")
                withTimeout(10_000) { vm.ui.first { !it.restricted } }
            } finally { withContext(Dispatchers.Main) { owner.clear() } }
        }
        test("synthetic trusted sensor frames reach collision and wrong-lane voice requests") {
            input("perception prepare")
            val job = SupervisorJob(); val scope = CoroutineScope(job + Dispatchers.Default)
            val file = File(context.cacheDir, "feature-sensors-${System.nanoTime()}.preferences_pb")
            val store = ProfileStore(PreferenceDataStoreFactory.create(scope = scope) { file })
            val frames = MutableStateFlow<PerceptionObservation?>(null)
            val source = object : PerceptionSource {
                override val observations = frames
                override val availability = "SYNTHETIC sensor contract test; no real camera/ADAS"
            }
            val navigation = object : NavSource {
                override val nav = MutableStateFlow(NavState(NavStatus.ROUTE_DRIVE, listOf(Maneuver(ManeuverType.MERGE_LEFT, 100.0))))
                override fun start() = Unit; override fun stop() = Unit
                override fun requestRoute(destination: Destination, waypoints: List<Destination>) = false
            }
            val lines = CopyOnWriteArrayList<String>()
            val speaker = object : Speaker {
                override fun speak(text: String, urgent: Boolean) { lines += text }
                override fun stop() = Unit; override fun release() = Unit
            }
            val session = DriveSessionController(scope, c.vehicleSource, navigation, NoLocationSource(), speaker, NoVoiceCommandSource(), store, perception = source)
            try {
                session.start(store.create("sensor-fixture", "1234"), Tier.CITY, HazardIndex(emptyList()))
                input("perception driving", Gear.DRIVE, 15)
                withTimeout(10_000) { session.state.first { it.phase == Phase.DRIVING } }
                check(lines.none { it.contains("충돌 위험 신호") || it.contains("차로 이동이 감지") })
                frames.value = PerceptionObservation(System.currentTimeMillis(), ObservationOrigin.VEHICLE_SENSOR, .99, collisionWarning = true)
                withTimeout(10_000) { while (lines.none { it.contains("충돌 위험 신호") }) delay(50) }
                delay(400)
                frames.value = PerceptionObservation(System.currentTimeMillis(), ObservationOrigin.VEHICLE_SENSOR, .99, collisionWarning = false)
                delay(500)
                frames.value = PerceptionObservation(System.currentTimeMillis(), ObservationOrigin.VEHICLE_SENSOR, .99, completedLaneChange = LateralDirection.RIGHT)
                withTimeout(10_000) { while (lines.none { it.contains("차로 이동이 감지") }) delay(50) }
                input("perception parking alignment", Gear.REVERSE, 0)
                frames.value = PerceptionObservation(System.currentTimeMillis(), ObservationOrigin.VEHICLE_SENSOR, .99, parkingAligned = false)
                withTimeout(10_000) { while (lines.none { it.contains("주차 정렬 확인") }) delay(50) }
                input("perception stop")
                val saved = withTimeout(10_000) { session.state.first { it.tripScore != null } }.tripScore!!
                check(saved.events.count { it.kind == TripEvent.Kind.SENSOR_WARNING } == 3)
                File(context.cacheDir, "validation-synthetic-sensor-evidence.json").writeText(JSONObject()
                    .put("source", "synthetic contract frames + emulator SDK speed/gear")
                    .put("warnings", org.json.JSONArray(lines.filter { it.contains("감지") || it.contains("주차 정렬") })).toString())
            } finally { session.stop(); job.cancelAndJoin(); file.delete() }
        }
        test("public data cache survives reload and failed refresh without retaining key") {
            val directory = File(context.cacheDir, "feature-public-${System.nanoTime()}").also { it.mkdirs() }
            val isolated = object : ContextWrapper(context) { override fun getFilesDir() = directory }
            try {
                val json = """{"resultCode":"00","totalCount":1,"items":{"item":{"afos_fid":"fixture","spot_nm":"fixture","la_crd":37.5,"lo_crd":127.0}}}"""
                val repo = HazardRepository(isolated, KoRoadClient { json })
                check(repo.hazards().isEmpty())
                check(repo.refresh("TEST-KEY-NOT-A-SECRET", 2024, "11680").getOrThrow() == 1)
                val before = File(directory, "public-hazards.json").readText()
                check(!before.contains("TEST-KEY"))
                val failed = HazardRepository(isolated, KoRoadClient { error("synthetic network outage") })
                check(failed.refresh("TEST-KEY", 2024, "11680").isFailure)
                check(File(directory, "public-hazards.json").readText() == before)
                check(HazardRepository(isolated).hazards().single().id == "fixture")
            } finally { directory.deleteRecursively() }
        }
        test("navigation SDK reconnect clears old data and resumes responses") {
            c.navSource.stop()
            check(c.navSource.nav.value.next == null)
            c.navSource.start()
            withTimeout(20_000) { c.navSource.nav.first { it.location != null } }
        }
        test("production perception remains explicitly unavailable") {
            check(c.perception.observations.value == null)
            check(c.perception.availability.contains("미연결"))
            check(c.profileStore.profiles.first() == profilesBefore)
        }
        input("features finished parked")
    }
    suspend fun seedRecovery() {
        input("crash prepare")
        withTimeout(10_000) { c.recoveryReady.first { it } }
        check(c.checkpoints.read() == null) { "An existing checkpoint must be recovered first" }
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(1_000)
        val beforeCount = c.profileStore.profiles.first().size
        val driver = c.profileStore.create("validation-recovery-${System.nanoTime()}", "1234")
        c.session.start(driver, Tier.CITY, HazardIndex(emptyList()))
        input("crash active drive", Gear.DRIVE, 20)
        val saved = withTimeout(15_000) {
            var result: SessionCheckpoint? = null
            while (result == null) { delay(200); result = c.checkpoints.read()?.takeIf { it.profileId == driver.id } }
            result
        }
        marker.writeText(JSONObject().put("profileId", driver.id).put("tripId", saved.trip.tripId)
            .put("pid", android.os.Process.myPid()).put("previousProfiles", beforeCount).put("readyToKill", true).toString())
        // The host force-stops this process while the SDK still reports Drive.
        delay(60_000)
        error("Host did not stop the process after checkpoint")
    }
    suspend fun verifyRecovery(cleanup: Boolean) {
        withTimeout(20_000) { c.recoveryReady.first { it } }
        val expected = JSONObject(marker.readText())
        check(expected.getInt("pid") != android.os.Process.myPid())
        val driver = c.profileStore.profiles.first().single { it.id == expected.getString("profileId") }
        check(driver.name.startsWith("validation-recovery-"))
        check(driver.trips.size == 1 && driver.trips.single().tripId == expected.getString("tripId"))
        check(driver.trips.single().provisional && driver.skill.xp == 0)
        check(c.checkpoints.read() == null && c.session.state.value.phase == Phase.IDLE)
        if (cleanup) {
            c.profileStore.delete(driver.id)
            check(c.profileStore.profiles.first().size == expected.getInt("previousProfiles"))
        }
    }
}
