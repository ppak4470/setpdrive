package kr.stepdrive.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kr.stepdrive.app.data.ProfileStore
import kr.stepdrive.app.location.NoLocationSource
import kr.stepdrive.app.navi.*
import kr.stepdrive.app.session.*
import kr.stepdrive.app.speech.*
import kr.stepdrive.app.vehicle.VehicleSignalSource
import kr.stepdrive.core.model.*
import kr.stepdrive.core.hazard.HazardIndex
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import org.junit.Test
import kotlin.test.*

class SessionVoiceFeaturesTest {
    private class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val now = AtomicLong(10_000)
        val store = ProfileStore(FeaturePreferences())
        val spoken = CopyOnWriteArrayList<String>()
        val samples = MutableStateFlow(VehicleSample(10_000, 30f, Gear.DRIVE))
        val commands = MutableSharedFlow<VoiceCommand>()
        val vehicle = object : VehicleSignalSource {
            override val samples = this@Fixture.samples
            override fun start() = Unit; override fun stop() = Unit
        }
        val voice = object : VoiceCommandSource {
            override val commands = this@Fixture.commands
            override fun listenOnce() = Unit; override fun release() = Unit
        }
        var reroutes = 0
        val nav = object : NavSource {
            override val nav = MutableStateFlow(NavState())
            override fun start() = Unit; override fun stop() = Unit
            override fun requestRoute(destination: Destination, waypoints: List<Destination>) = true
            override fun requestReroute(): Boolean { reroutes++; return true }
        }
        val speaker = object : Speaker {
            override fun speak(text: String, urgent: Boolean) { spoken += text }
            override fun stop() = Unit; override fun release() = Unit
        }
        val controller = DriveSessionController(scope, vehicle, nav, NoLocationSource(), speaker, voice, store, nowMs = now::get)
        suspend fun start() {
            controller.start(store.create("음성검증", "1234"), Tier.CITY, HazardIndex(emptyList()))
            controller.state.first { it.phase == Phase.DRIVING }
            commands.subscriptionCount.first { it > 0 }
        }
        suspend fun command(c: VoiceCommand) { commands.emit(c); delay(30) }
        suspend fun close() { controller.stop(); scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
    private fun scenario(block: suspend Fixture.() -> Unit) = runBlocking {
        withTimeout(15_000) { val f = Fixture(); try { f.start(); f.block() } finally { f.close() } }
    }
    @Test fun yesAloneOrAfterCancellationCannotEndPractice() = scenario {
        command(VoiceCommand.YES)
        assertEquals(Phase.DRIVING, controller.state.value.phase)
        command(VoiceCommand.STOP); command(VoiceCommand.NO); command(VoiceCommand.YES)
        assertEquals(Phase.DRIVING, controller.state.value.phase)
        assertTrue(store.profiles.first().single().trips.isEmpty())
    }
    @Test fun expiredConfirmationDoesNotEndPractice() = scenario {
        command(VoiceCommand.STOP); now.addAndGet(11_000); command(VoiceCommand.YES)
        assertEquals(Phase.DRIVING, controller.state.value.phase)
    }
    @Test fun confirmedEndSavesOneProvisionalRecordWithoutParkingSuccess() = scenario {
        command(VoiceCommand.STOP); command(VoiceCommand.YES)
        val report = controller.state.first { it.tripScore != null }.tripScore!!
        assertTrue(report.provisional); assertEquals(0, report.xp)
        assertTrue(report.events.none { it.kind == TripEvent.Kind.PARKING_DONE })
        assertEquals(1, store.profiles.first().single().trips.size)
    }
    @Test fun rerouteCooldownPreventsRepeatedRequestsAndAllowsRetry() = scenario {
        command(VoiceCommand.REROUTE); command(VoiceCommand.REROUTE)
        assertEquals(1, reroutes)
        now.addAndGet(16_000); command(VoiceCommand.REROUTE)
        assertEquals(2, reroutes)
    }
    @Test fun nextCannotAdvanceDrivingOrFastReverseAndRepeatStillWorks() = scenario {
        val initial = controller.state.value.parkingStep
        command(VoiceCommand.NEXT)
        assertEquals(initial, controller.state.value.parkingStep)
        samples.value = VehicleSample(10_100, 10f, Gear.REVERSE)
        controller.state.first { it.phase == Phase.PARKING }
        now.set(10_100)
        val step = controller.state.value.parkingStep
        command(VoiceCommand.NEXT)
        assertEquals(step, controller.state.value.parkingStep)
        assertTrue(spoken.any { it.contains("속도를 확인") })
        val before = spoken.size; command(VoiceCommand.REPEAT)
        assertTrue(spoken.size > before)
    }
    @Test fun helpDescribesAvailableCommands() = scenario {
        command(VoiceCommand.HELP)
        assertTrue(spoken.last().contains("재탐색")); assertTrue(spoken.last().contains("다음"))
    }
    @Test fun stopAndRerouteRejectNegatedOrConversationalSpeech() {
        assertEquals(VoiceCommand.STOP, CommandParser.parse("연습 종료"))
        assertEquals(VoiceCommand.REROUTE, CommandParser.parse("경로 다시 찾아줘"))
        for (text in listOf("연습 종료하지 마", "경로 재탐색하지 마", "연습 종료가 뭐야", "네 아니요")) assertNull(CommandParser.parse(text))
    }
}
