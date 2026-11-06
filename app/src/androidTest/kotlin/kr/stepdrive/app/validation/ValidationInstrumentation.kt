package kr.stepdrive.app.validation

import android.app.Activity
import android.app.Application
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.Instrumentation
import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kr.stepdrive.app.*
import kr.stepdrive.app.data.ProfileStore
import kr.stepdrive.app.location.NoLocationSource
import kr.stepdrive.app.navi.*
import kr.stepdrive.app.session.*
import kr.stepdrive.app.speech.NoVoiceCommandSource
import kr.stepdrive.app.vehicle.PleosVehicleSource
import kr.stepdrive.app.driving.CarUxRestrictionsMonitor
import kr.stepdrive.app.service.PracticeService
import kr.stepdrive.core.hazard.HazardIndex
import kr.stepdrive.core.model.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

/** On-device checks only. Uses separate temporary records, never the user's profiles. */
class ValidationInstrumentation : Instrumentation() {
    private var mode = "adapters"
    private var cleanupProfileId: String? = null
    private val results = mutableListOf<String>()
    private var failures = 0
    private val applicationReady = CountDownLatch(1)
    private val app get() = targetContext.applicationContext as StepDriveApp
    private val c get() = app.container
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        mode = arguments?.getString("mode") ?: mode
        cleanupProfileId = arguments?.getString("cleanupProfileId")
        start()
    }
    override fun callApplicationOnCreate(application: Application) {
        super.callApplicationOnCreate(application)
        applicationReady.countDown()
    }
    override fun onStart() {
        try {
            check(applicationReady.await(25, TimeUnit.SECONDS)) { "Application initialization timed out" }
            val contestEmulator = BuildConfig.APPLICATION_ID == "kr.stepdrive.app.contest" &&
                android.os.Build.HARDWARE in setOf("ranchu", "goldfish")
            check(BuildConfig.APPLICATION_ID == "kr.stepdrive.app.validation" || contestEmulator) {
                "Only an isolated validation app or contest emulator may be tested"
            }
            runBlocking {
                cleanupProfileId?.let { id ->
                    val owned = c.profileStore.profiles.first().single { it.id == id }
                    check(owned.name.startsWith("validation-lifecycle-"))
                    c.profileStore.delete(id)
                }
                if (mode == "route") test("live vehicle five turns and parking") { fullRoute() }
                else if (mode == "features") FeatureChecks(targetContext, c, this@ValidationInstrumentation).run { name, block -> test(name, block) }
                else if (mode == "recovery-seed") FeatureChecks(targetContext, c, this@ValidationInstrumentation).seedRecovery()
                else if (mode == "recovery-check" || mode == "recovery-cleanup") test("process restart restores exactly one provisional trip; cleanup=${mode == "recovery-cleanup"}") {
                    FeatureChecks(targetContext, c, this@ValidationInstrumentation).verifyRecovery(mode == "recovery-cleanup")
                }
                else if (mode == "mistakes") DriverMistakeChecks(targetContext, c).run { name, block -> test(name, block) }
                else if (mode == "lifecycle") test("foreground drive background recreation and single save") { foregroundLifecycle() }
                else if (mode == "permission-denied") test("actual Android speed denial leaves speed unavailable") {
                    check(targetContext.checkSelfPermission("android.car.permission.CAR_SPEED") == PackageManager.PERMISSION_DENIED)
                    delay(5_000)
                    check(c.vehicleSource.samples.value.speedKmh == null)
                }
                else if (mode == "permission-restored") test("actual Android speed reallow restores fresh samples") {
                    check(targetContext.checkSelfPermission("android.car.permission.CAR_SPEED") == PackageManager.PERMISSION_GRANTED)
                    c.onPermissionsChanged()
                    withTimeout(20_000) { c.vehicleSource.samples.first { it.speedKmh != null && System.currentTimeMillis() - it.speedTMs in 0..3_000 } }
                }
                else if (mode == "voice") test("speech request lifecycle") {
                    startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    c.speaker.speak("스텝드라이브 음성 출력 테스트입니다")
                    delay(5_000)
                    c.voiceCommands.listenOnce()
                    delay(12_000)
                    c.voiceCommands.release(); c.voiceCommands.refreshPermissions()
                }
                else {
                    test("speed permission adapter deny allow revoke allow") {
                        val gate = object : ContextWrapper(targetContext) {
                            var speedAllowed = false
                            override fun checkSelfPermission(permission: String): Int =
                                if (permission == "android.car.permission.CAR_SPEED" && !speedAllowed) PackageManager.PERMISSION_DENIED
                                else super.checkSelfPermission(permission)
                        }
                        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                        val source = PleosVehicleSource(gate, scope)
                        try {
                            source.start()
                            withTimeout(15_000) { source.samples.first { it.gear == Gear.PARK } }
                            check(source.samples.value.speedKmh == null)
                            gate.speedAllowed = true; source.refreshPermissions()
                            withTimeout(15_000) { source.samples.first { it.speedKmh != null } }
                            gate.speedAllowed = false; source.refreshPermissions()
                            check(source.samples.value.speedKmh == null)
                            delay(2_000)
                            check(source.samples.value.speedKmh == null) { "Late speed callback restored revoked data" }
                            gate.speedAllowed = true; source.refreshPermissions()
                            withTimeout(15_000) { source.samples.first { it.speedKmh != null } }
                        } finally { source.stop(); scope.cancel() }
                    }
                    test("vehicle subscription restart three times") {
                        repeat(3) {
                            c.vehicleSource.stop()
                            check(c.vehicleSource.samples.value.speedKmh == null)
                            check(c.vehicleSource.samples.value.gear == Gear.UNKNOWN)
                            c.vehicleSource.start()
                            withTimeout(20_000) { c.vehicleSource.samples.first { it.speedKmh != null && it.gear == Gear.PARK } }
                        }
                    }
                    test("navigation adapter restart") {
                        c.navSource.stop(); c.navSource.start()
                        withTimeout(20_000) { c.navSource.nav.first { it.location != null } }
                    }
                    test("screen restrictions stop and reconnect fail closed") {
                        val monitor = CarUxRestrictionsMonitor(targetContext)
                        try {
                            repeat(3) {
                                check(monitor.restricted.value)
                                monitor.start()
                                withTimeout(15_000) { monitor.restricted.first { !it } }
                                monitor.stop()
                                check(monitor.restricted.value)
                                delay(300)
                                check(monitor.restricted.value)
                            }
                        } finally { monitor.stop() }
                    }
                    test("Android file records persist across store recreation") { fileStore() }
                    test("activity recreated five times") { recreateActivity() }
                }
            }
        } catch (e: Throwable) { failures++; results += "FAIL runner: ${e.javaClass.simpleName}: ${e.message}" }
        val output = results.joinToString("\n")
        File(targetContext.cacheDir, "validation-$mode-result.txt").writeText(output)
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", "\n$output\n"); putInt("failures", failures) })
    }
    private suspend fun test(name: String, block: suspend () -> Unit) {
        try { block(); results += "PASS $name" }
        catch (e: Throwable) {
            failures++; results += "FAIL $name: ${e.javaClass.simpleName}: ${e.message} at ${e.stackTrace.firstOrNull()}"
            android.util.Log.e("StepDriveValidation", name, e)
        }
        sendStatus(0, Bundle().apply { putString("stream", results.last()+"\n") })
    }
    private suspend fun fileStore() {
        val file = File(targetContext.cacheDir, "validation-file-${System.nanoTime()}.preferences_pb")
        var job = SupervisorJob()
        try {
            var store = ProfileStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file })
            val p = store.create("instrumentation-only", "1234")
            job.cancelAndJoin(); job = SupervisorJob()
            store = ProfileStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file })
            val restored = store.profiles.first().single()
            check(restored.id == p.id && store.verifyPin(restored, "1234"))
        } finally { job.cancelAndJoin(); file.delete() }
    }
    private fun recreateActivity(expectedPhase: Phase = Phase.IDLE, times: Int = 5) {
        val resumed = LinkedBlockingQueue<Activity>()
        val callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { if (a is MainActivity) resumed.offer(a) }
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        }
        app.registerActivityLifecycleCallbacks(callback)
        try {
            // singleTask may deliver onNewIntent to an existing Activity. startActivitySync waits
            // for a new instance in that case, so use lifecycle callbacks with a bounded wait.
            targetContext.startActivity(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            var activity = checkNotNull(resumed.poll(15, TimeUnit.SECONDS))
            repeat(times) {
                resumed.clear()
                runOnMainSync { activity.recreate() }
                activity = checkNotNull(resumed.poll(15, TimeUnit.SECONDS)) { "Activity did not resume" }
                check(c.session.state.value.phase == expectedPhase)
            }
        } finally { app.unregisterActivityLifecycleCallbacks(callback) }
    }
    @Suppress("DEPRECATION")
    private suspend fun foregroundLifecycle() {
        val status = File(targetContext.cacheDir, "validation-live.json")
        status.writeText("{\"stage\":\"preparing\"}")
        val previousActive = c.memory.activeProfileId
        val previousUnlock = c.memory.unlockedAtMs
        val before = c.profileStore.profiles.first()
        val driver = c.profileStore.create("validation-lifecycle-${System.nanoTime()}", "1234")
        val manager = targetContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        fun foreground() = manager.getRunningServices(100).any {
            it.service.className == PracticeService::class.java.name && it.foreground
        }
        var monitor: Job? = null
        try {
            withTimeout(20_000) { c.vehicleSource.samples.first { it.gear == Gear.PARK && it.speedKmh != null } }
            startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            c.switchableNav?.setSimulated(true)
            check(c.simNav.requestRoute(Destination("백그라운드 검증", LatLng(37.56,127.08)), emptyList()))
            c.session.start(driver, Tier.CITY, HazardIndex(emptyList()))
            val begin = System.currentTimeMillis()
            monitor = c.scope.launch {
                while (isActive) {
                    val s = c.session.state.value
                    status.writeText(JSONObject().apply {
                        put("stage", "driving"); put("phase", s.phase.name); put("parking", s.parkingStep.name)
                        put("remaining", if (System.currentTimeMillis() - begin < 45_000) 3200 else 0)
                        put("next", "RIGHT"); put("speed", s.sample.speedKmh); put("gear", s.sample.gear.name)
                    }.toString())
                    delay(250)
                }
            }
            withTimeout(20_000) { c.session.state.first { it.phase == Phase.DRIVING && (it.sample.speedKmh ?: 0f) > 5f } }
            withTimeout(10_000) { while (!foreground()) delay(200) }
            targetContext.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            delay(5_000)
            check(foreground() && c.session.state.value.phase == Phase.DRIVING) { "Background state: foreground=${foreground()}, phase=${c.session.state.value.phase}" }
            check(c.session.state.value.profile?.id == driver.id) { "Active driver changed" }
            val notifications = targetContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // Android may defer displaying a new foreground notification briefly.
            withTimeout(20_000) {
                while (notifications.activeNotifications.none { it.notification.channelId == "practice" }) delay(200)
            }
            recreateActivity(Phase.DRIVING, 3)
            val result = withTimeout(100_000) { c.session.state.first { it.tripScore != null } }
            check(result.saveError == null)
            check(c.profileStore.profiles.first().first { it.id == driver.id }.trips.size == 1)
            withTimeout(10_000) { while (foreground()) delay(200) }
            status.writeText("{\"stage\":\"done\"}")
        } finally {
            monitor?.cancelAndJoin(); c.session.stop(); c.simNav.stop(); c.switchableNav?.setSimulated(false)
            c.profileStore.delete(driver.id)
            c.memory.activeProfileId = previousActive; c.memory.unlockedAtMs = previousUnlock
            check(c.profileStore.profiles.first() == before) { "Unrelated profiles changed during lifecycle verification" }
        }
    }
    private suspend fun fullRoute() {
        val status = File(targetContext.cacheDir, "validation-live.json")
        status.writeText("{\"stage\":\"preparing\"}")
        withTimeout(25_000) { c.vehicleSource.samples.first { it.gear == Gear.PARK && it.speedKmh != null && it.turnSignal != TurnSignal.UNKNOWN } }
        val file = File(targetContext.cacheDir, "validation-trip-${System.nanoTime()}.preferences_pb")
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val store = ProfileStore(PreferenceDataStoreFactory.create(scope = scope) { file })
        val sim = SimulatedNavSource(scope, c.vehicleSource)
        val controller = DriveSessionController(scope, c.vehicleSource, sim, NoLocationSource(), c.speaker, NoVoiceCommandSource(), store)
        try {
            val driver = store.create("instrumentation-route", "1234")
            check(sim.requestRoute(Destination("검증 목적지", LatLng(37.56,127.08)), emptyList()))
            controller.start(driver, Tier.CITY, HazardIndex(emptyList()))
            val monitor = scope.launch {
                while (isActive) {
                    val s = controller.state.value
                    val n = sim.nav.value
                    status.writeText(JSONObject().apply {
                        put("stage", "driving"); put("phase", s.phase.name); put("parking", s.parkingStep.name)
                        put("remaining", n.remainDistanceM); put("next", n.next?.type?.name); put("distance", n.next?.distanceM)
                        put("speed", s.sample.speedKmh); put("gear", s.sample.gear.name); put("signal", s.sample.turnSignal.name)
                    }.toString())
                    delay(250)
                }
            }
            val report = withTimeout(480_000) { controller.state.first { it.tripScore != null } }
            monitor.cancelAndJoin()
            val trip = checkNotNull(report.tripScore)
            File(targetContext.cacheDir, "validation-trip-result.json").writeText(Json.encodeToString(trip))
            check(report.saveError == null)
            check(trip.distanceKm in 3.1..3.5) { "Full route distance was ${trip.distanceKm}km" }
            check(trip.events.count { it.kind == TripEvent.Kind.SIGNAL_OK } == 5) { "Expected five evaluated turns: ${trip.events.map { it.kind }}" }
            check(trip.events.none { it.kind == TripEvent.Kind.SIGNAL_MISSED })
            check(trip.events.count { it.kind == TripEvent.Kind.PARKING_DONE } == 1)
            check(store.profiles.first().single().trips.size == 1)
            status.writeText("{\"stage\":\"done\"}")
        } finally { controller.stop(); sim.stop(); job.cancelAndJoin(); file.delete() }
    }
}
