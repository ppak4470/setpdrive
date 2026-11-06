package kr.stepdrive.app.validation

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kr.stepdrive.app.data.ProfileStore
import kr.stepdrive.app.di.AppContainer
import kr.stepdrive.app.location.NoLocationSource
import kr.stepdrive.app.navi.Destination
import kr.stepdrive.app.navi.NavSource
import kr.stepdrive.app.session.DriveSessionController
import kr.stepdrive.app.speech.NoVoiceCommandSource
import kr.stepdrive.app.speech.Speaker
import kr.stepdrive.core.hazard.HazardIndex
import kr.stepdrive.core.model.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/** Real emulator SDK vehicle inputs + explicit test navigation; no user's profile is changed. */
internal class DriverMistakeChecks(private val context: Context, private val c: AppContainer) {
    private val live = File(context.cacheDir, "validation-mistakes-live.json")
    private val evidence = mutableListOf<JSONObject>()
    private var requestId = 0

    private class TestNav : NavSource {
        override val nav = MutableStateFlow(NavState())
        override fun start() = Unit
        override fun stop() = Unit
        override fun requestRoute(destination: Destination, waypoints: List<Destination>) = false
        fun turn(type: ManeuverType, distance: Double) {
            nav.value = NavState(NavStatus.ROUTE_DRIVE, listOf(Maneuver(type, distance, "실수 검증")))
        }
    }

    private inner class CaptureSpeaker : Speaker {
        val lines = CopyOnWriteArrayList<String>()
        override fun speak(text: String, urgent: Boolean) {
            lines += text
            c.speaker.speak(text, urgent)
        }
        override fun stop() = c.speaker.stop()
        override fun release() = Unit
        suspend fun await(count: Int = 1, matches: (String) -> Boolean) {
            withTimeout(15_000) { while (lines.count(matches) < count) delay(100) }
        }
    }

    private suspend fun input(name: String, gear: Gear = Gear.PARK, speed: Int = 0,
                              signal: TurnSignal = TurnSignal.OFF, brake: Boolean = true, steering: Int = 0) {
        val command = JSONObject().apply {
            put("stage", "input"); put("id", ++requestId); put("name", name)
            put("gear", gear.name); put("speed", speed); put("signal", signal.name)
            put("brake", if (brake) 30 else 0); put("steering", steering)
        }
        live.writeText(command.toString())
        val received = withTimeout(15_000) {
            c.vehicleSource.samples.first {
                it.gear == gear && it.speedKmh?.let { v -> abs(v - speed) < 0.7f } == true &&
                    System.currentTimeMillis() - it.speedTMs in 0..3_000 && it.turnSignal == signal &&
                    // Pleos_Connect 2.1 console uses clockwise-positive; its SDK callback is opposite.
                    it.brakePressed == brake && it.steeringAngleDeg?.let { a -> abs(a + steering) < 2 } == true
            }
        }
        evidence += command.put("observed", JSONObject().apply {
            put("gear", received.gear.name); put("speed", received.speedKmh)
            put("signal", received.turnSignal.name); put("brake", received.brakePressed)
            put("steering", received.steeringAngleDeg)
        })
    }

    private suspend fun session(name: String, action: suspend (DriveSessionController, TestNav, CaptureSpeaker, ProfileStore) -> Unit) {
        input("$name prepare")
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val file = File(context.cacheDir, "validation-mistake-${System.nanoTime()}.preferences_pb")
        val store = ProfileStore(PreferenceDataStoreFactory.create(scope = scope) { file })
        val nav = TestNav()
        val speaker = CaptureSpeaker()
        val controller = DriveSessionController(scope, c.vehicleSource, nav, NoLocationSource(), speaker, NoVoiceCommandSource(), store)
        try {
            controller.start(store.create("instrumentation-mistake", "1234"), Tier.CITY, HazardIndex(emptyList()))
            action(controller, nav, speaker, store)
        } finally {
            evidence += JSONObject().put("case", name).put("spokenRequests", JSONArray(speaker.lines))
            controller.stop()
            job.cancelAndJoin()
            file.delete()
        }
    }

    suspend fun run(test: suspend (String, suspend () -> Unit) -> Unit) {
        val before = c.profileStore.profiles.first()
        try {
            test("left guidance right indicator produces one direction notice") {
                session("left mismatch") { _, nav, speaker, _ ->
                    nav.turn(ManeuverType.MERGE_LEFT, 150.0)
                    input("left guidance right indicator", Gear.DRIVE, 30, TurnSignal.RIGHT, false)
                    speaker.await { it.startsWith("안내는 왼쪽, 깜빡이는 오른쪽") }
                    delay(3_500)
                    check(speaker.lines.count { it.startsWith("안내는") } == 1)
                }
            }
            test("right guidance left indicator produces direction notice") {
                session("right mismatch") { _, nav, speaker, _ ->
                    nav.turn(ManeuverType.RIGHT, 150.0)
                    input("right guidance left indicator", Gear.DRIVE, 30, TurnSignal.LEFT, false)
                    speaker.await { it.startsWith("안내는 오른쪽, 깜빡이는 왼쪽") }
                }
            }
            test("correct then opposite repeats warning and saves missed indicator once") {
                session("changed indicator") { controller, nav, speaker, store ->
                    nav.turn(ManeuverType.LEFT, 200.0)
                    input("initial correct indicator", Gear.DRIVE, 30, TurnSignal.LEFT, false)
                    speaker.await { it == "좋아요, 깜빡이 확인했어요" }
                    input("changed to opposite", Gear.DRIVE, 30, TurnSignal.RIGHT, false)
                    speaker.await { it.startsWith("안내는") }
                    input("corrected early", Gear.DRIVE, 30, TurnSignal.LEFT, false)
                    speaker.await(2) { it == "좋아요, 깜빡이 확인했어요" }
                    input("second opposite incident", Gear.DRIVE, 30, TurnSignal.RIGHT, false)
                    speaker.await(2) { it.startsWith("안내는") }
                    nav.turn(ManeuverType.LEFT, 10.0)
                    delay(800)
                    nav.turn(ManeuverType.ARRIVE, 0.0)
                    delay(800)
                    input("stop after test", Gear.DRIVE, 0, TurnSignal.OFF)
                    input("park after test")
                    val state = withTimeout(15_000) { controller.state.first { it.tripScore != null } }
                    val trip = checkNotNull(state.tripScore)
                    check(state.saveError == null && trip.signalRate == 0.0) { "Incorrect signal rate: ${trip.signalRate}" }
                    check(trip.events.count { it.kind == TripEvent.Kind.SIGNAL_MISSED } == 1)
                    check(trip.events.none { it.kind == TripEvent.Kind.SIGNAL_OK })
                    check(trip.events.count { it.kind == TripEvent.Kind.SIGNAL_MISMATCH_NOTICE } == 2)
                    check(store.profiles.first().single().trips.size == 1)
                    File(context.cacheDir, "validation-mistakes-trip.json").writeText(Json.encodeToString(trip))
                }
            }
            test("moving D to R and immediate reverse overspeed both warn") {
                session("moving shift") { _, _, speaker, _ ->
                    input("moving drive", Gear.DRIVE, 4)
                    input("moving reverse", Gear.REVERSE, 8)
                    speaker.await { it == "완전히 멈춘 뒤 기어를 바꾸세요" }
                    speaker.await { it == "조금 더 천천히" }
                }
            }
            test("gear selection without brake warns") {
                session("no brake shift") { _, _, speaker, _ ->
                    input("drive without brake", Gear.DRIVE, 0, brake = false)
                    speaker.await { it == "브레이크를 밟고 기어를 바꾸세요" }
                }
            }
            test("stopped brake held shift does not create gear error") {
                session("safe shift") { _, _, speaker, _ ->
                    input("stopped drive with brake", Gear.DRIVE, 0)
                    input("stopped reverse with brake", Gear.REVERSE, 0)
                    delay(1_500)
                    check(speaker.lines.none { it == "완전히 멈춘 뒤 기어를 바꾸세요" || it == "브레이크를 밟고 기어를 바꾸세요" })
                }
            }
            test("steering without indicator produces reference notice") {
                session("steering signal off") { _, _, speaker, _ ->
                    input("steering with signal off", Gear.DRIVE, 40, TurnSignal.OFF, false, 120)
                    speaker.await { it == "핸들이 많이 돌았는데 깜빡이가 꺼져 있어요" }
                    input("opposite steering sign", Gear.DRIVE, 40, TurnSignal.OFF, false, -120)
                    check(speaker.lines.none { it.contains("차로 이탈") || it.contains("차선 변경 감지") })
                }
            }
            test("test sessions leave existing user profiles unchanged") {
                check(c.profileStore.profiles.first() == before)
            }
        } finally {
            input("restore parked emulator")
            File(context.cacheDir, "validation-mistakes-evidence.json").writeText(evidence.joinToString(",", "[", "]"))
            live.writeText("{\"stage\":\"done\"}")
        }
    }
}
