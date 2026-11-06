package kr.stepdrive.app.session

import android.content.Context
import android.util.Log
import kr.stepdrive.app.service.PracticeService
import kr.stepdrive.core.coach.OperationWatch
import kr.stepdrive.core.model.TripEvent
import kr.stepdrive.core.report.TripSummary
import kr.stepdrive.app.diag.SignalCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kr.stepdrive.core.practice.PracticeProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.stepdrive.app.data.ProfileStore
import kr.stepdrive.app.data.SessionMemory
import kr.stepdrive.app.location.LocationSource
import kr.stepdrive.app.navi.NavSource
import kr.stepdrive.app.speech.Speaker
import kr.stepdrive.app.speech.VoiceCommand
import kr.stepdrive.app.speech.VoiceCommandSource
import kr.stepdrive.app.vehicle.VehicleSignalSource
import kr.stepdrive.core.coach.CoachEngine
import kr.stepdrive.core.coach.DistanceSmoother
import kr.stepdrive.core.coach.Priority
import kr.stepdrive.core.coach.hasDirectionMismatch
import kr.stepdrive.core.navigation.RoutePhase
import kr.stepdrive.core.navigation.RouteDeviationMonitor
import kr.stepdrive.core.safety.PerceptionMonitor
import kr.stepdrive.app.safety.PerceptionSource
import kr.stepdrive.app.safety.UnavailablePerceptionSource
import kr.stepdrive.app.data.SessionCheckpoint
import kr.stepdrive.app.data.SessionCheckpointStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kr.stepdrive.core.hazard.HazardIndex
import kr.stepdrive.core.model.Gear
import kr.stepdrive.core.model.NavState
import kr.stepdrive.core.model.Tier
import kr.stepdrive.core.model.TripScore
import kr.stepdrive.core.model.VehicleSample
import kr.stepdrive.core.model.freshAt
import kr.stepdrive.core.parking.ParkingCoach
import kr.stepdrive.core.parking.ParkingPrompt
import kr.stepdrive.core.parking.ParkingStep
import kr.stepdrive.core.profile.DriverProfile
import kr.stepdrive.core.score.ScoreEngine
import java.util.UUID

enum class Phase { IDLE, READY, DRIVING, PARKING, REPORT }

data class SessionState(
    val phase: Phase = Phase.IDLE,
    val sample: VehicleSample = VehicleSample(0),
    val nav: NavState = NavState(),
    val lastCoachText: String = "",
    val lastCoachPriority: Priority? = null,
    val lastCoachAtMs: Long? = null,
    val hazardAheadM: Int? = null,
    val parkingStep: ParkingStep = ParkingStep.ALIGN,
    val parkingPrompt: ParkingPrompt? = null,
    val tripScore: TripScore? = null,
    val profile: DriverProfile? = null,
    val saveError: String? = null,
    val routeNotice: String? = null,
    val perceptionStatus: String = "차로·장애물 센서 미연결",
)

/**
 * Runs one practice drive: feeds vehicle + navigation into the score engine, the live coach and the parking coach,
 * speaks what they produce, and closes the trip into the driver's profile when the car is parked again.
 */
class DriveSessionController(
    private val scope: CoroutineScope,
    private val vehicle: VehicleSignalSource,
    private val navSource: NavSource,
    private val location: LocationSource,
    private val speaker: Speaker,
    private val voice: VoiceCommandSource,
    private val profileStore: ProfileStore,
    private val appContext: Context? = null,
    private val memory: SessionMemory? = null,
    private val checkpoints: SessionCheckpointStore? = null,
    private val perception: PerceptionSource = UnavailablePerceptionSource(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var tickJob: Job? = null
    private var voiceJob: Job? = null
    private var score: ScoreEngine? = null
    private var coach: CoachEngine? = null
    private var parking: ParkingCoach? = null
    private val opWatch = OperationWatch()
    private var smoother = DistanceSmoother()
    private var hazards: HazardIndex = HazardIndex(emptyList())
    private var hasMoved = false
    private var parkingDone = false
    private var practiceProgress = PracticeProgress(null)
    private var generation = 0L
    private var checkpointJob: Job? = null
    private var checkpointTripId: String? = null
    private var stopRequestedAt: Long? = null
    private var lastRerouteAt: Long? = null
    private var lastRoutePhase: RoutePhase? = null
    private var routeDeviation = RouteDeviationMonitor()
    private var perceptionMonitor = PerceptionMonitor()

    /** Arms a session for [profile] on a course of [tier]; the trip starts when the car leaves Park. */
    @Synchronized fun start(profile: DriverProfile, tier: Tier, hazardIndex: HazardIndex) {
        stop()
        val run = generation
        hazards = hazardIndex
        val tripId = UUID.randomUUID().toString()
        checkpointTripId = tripId
        checkpoints?.begin(tripId)
        stopRequestedAt = null; lastRerouteAt = null; lastRoutePhase = null
        routeDeviation = RouteDeviationMonitor(); perceptionMonitor = PerceptionMonitor()
        score = ScoreEngine(tripId, tier, hazards)
        val level = profile.skill.feedbackLevel
        coach = CoachEngine(hazards, feedbackLevel = level)
        parking = ParkingCoach()
        practiceProgress = PracticeProgress(profile.openPractice)
        opWatch.reset()
        Log.i(TAG, "session armed: tier=$tier feedbackLevel=$level")
        appContext?.let { PracticeService.start(it) }
        memory?.sessionRunning = true
        SignalCheck.reset("주차체크", "기어 R로 바꾸면 1단계부터 진행됩니다")
        SignalCheck.reset("회전코칭", "회전 안내가 오면 확인됩니다")
        smoother = DistanceSmoother()
        hasMoved = false
        parkingDone = false
        _state.value = SessionState(phase = Phase.READY, profile = profile, perceptionStatus = perception.availability)
        if (checkpoints != null) checkpointJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val snapshot = synchronized(this@DriveSessionController) {
                    if (run != generation || !hasMoved || _state.value.phase in setOf(Phase.IDLE, Phase.REPORT)) null
                    else score?.checkpoint(nowMs())
                }
                if (snapshot != null) runCatching { checkpoints.write(SessionCheckpoint(profile.id, snapshot, System.currentTimeMillis())) }
                    .onFailure { SignalCheck.fail("세션보존", "중간 기록 저장 실패") }
                delay(5_000)
            }
        }

        tickJob = scope.launch {
            combine(vehicle.samples, navSource.nav, location.fix) { s, n, f ->
                // Prefer the navigation's position; fall back to fused location when not routing.
                val fresh = n.freshAt(s.tMs)
                if (fresh.location == null && f != null && s.tMs - f.tMs in 0..10_000)
                    Triple(s, fresh.copy(location = f.position, headingDeg = f.headingDeg, locationTMs = f.tMs), f)
                else Triple(s, fresh, f)
            }.collect { (s, n, _) -> onTick(s, n, run) }
        }
        voiceJob = scope.launch { voice.commands.collect { onVoice(it, run) } }
    }

    private suspend fun onTick(sample: VehicleSample, rawNav: NavState, run: Long) {
        var result: TripScore? = null
        var driver: DriverProfile? = null
        synchronized(this) {
        if (run != generation) return
        val s = sample.freshAt()
        // Between navigation updates, integrate speed so distances keep moving (and the coach fires on time).
        val n = smoother.apply(rawNav, s)
        val st = _state.value
        if (st.phase == Phase.REPORT || st.phase == Phase.IDLE) return
        val moving = abs(s.speedKmh ?: 0f) > 0.5f
        if (moving) hasMoved = true

        val phase = when {
            s.gear == Gear.REVERSE -> Phase.PARKING
            s.gear == Gear.PARK && hasMoved && s.speedKmh?.let { abs(it) <= 0.5f } == true -> Phase.REPORT
            s.gear == Gear.PARK -> Phase.READY
            else -> Phase.DRIVING
        }

        score?.onTick(s, n)
        practiceProgress.onTick(s, n)
        if (lastRoutePhase != n.routePhase) {
            lastRoutePhase = n.routePhase
            n.routeMessage?.let { text ->
                speaker.speak(text)
                score?.note(TripEvent.Kind.ROUTE_NOTICE, s.tMs, text)
            }
        }
        if (routeDeviation.tick(n, s.tMs)) requestReroute(s.tMs)
        val perceptionAlerts = perceptionMonitor.tick(perception.observations.value, s, n)
        var coachText = st.lastCoachText
        var coachPriority = st.lastCoachPriority
        var coachAt = st.lastCoachAtMs
        if (coachPriority == Priority.DIRECTION_CHECK && !hasDirectionMismatch(s, n)) {
            coachText = ""
            coachPriority = null
        }
        var prompt = st.parkingPrompt
        // Gear-handling notices (not scored): spoken once each, written to the trip log.
        opWatch.tick(s, parking?.step == ParkingStep.CHECK || parkingDone)?.let { nt ->
            Log.i(TAG, "notice[${nt.kind}]: ${nt.text}")
            SignalCheck.ok("기어안내", "${nt.kind} → ${nt.text}")
            speaker.speak(nt.text, urgent = true)
            score?.note(TripEvent.Kind.GEAR_NOTICE, s.tMs, nt.text)
            coachText = nt.text
            coachPriority = Priority.NOTICE
            coachAt = s.tMs
        }
        when (phase) {
            Phase.DRIVING -> if (perceptionAlerts.isEmpty()) coach?.tick(s, n)?.forEach { ev ->
                Log.i(TAG, "coach[${ev.priority}]: ${ev.text}")
                SignalCheck.ok("코칭", "${ev.text}")
                when (ev.priority) {
                    Priority.TURN, Priority.PRAISE -> SignalCheck.ok("회전코칭", "[${ev.priority}] ${ev.text}")
                    Priority.NOTICE -> SignalCheck.ok("참고안내", ev.text)
                    Priority.DIRECTION_CHECK -> SignalCheck.ok("방향확인", ev.text)
                    Priority.COACH -> if (ev.text.contains("깜빡이")) SignalCheck.ok("회전코칭", "[COACH] ${ev.text}")
                    else -> Unit
                }
                speaker.speak(ev.text, urgent = ev.priority == Priority.WARNING)
                if (ev.priority == Priority.NOTICE) score?.note(if (ev.text.contains("제한속도")) TripEvent.Kind.SLOW_NOTICE else TripEvent.Kind.LANE_NOTICE, s.tMs, ev.text)
                if (ev.priority == Priority.DIRECTION_CHECK) score?.note(TripEvent.Kind.SIGNAL_MISMATCH_NOTICE, s.tMs, ev.text)
                coachText = ev.text; coachPriority = ev.priority; coachAt = s.tMs
            }

            else -> Unit
        }

        // Feed Park and Drive as well: Park completes the checklist and Drive resets an unfinished attempt.
        parking?.tick(s)?.forEach { p ->
            Log.i(TAG, "parking[${p.step}]: ${p.text}")
            SignalCheck.ok("코칭", "주차 코치 ${p.step}")
            SignalCheck.ok("주차체크", if (p.isWarning) "경고: ${p.text}" else "${p.step.index}단계 ${p.step.short}")
            speaker.speak(p.text, urgent = p.isWarning)
            prompt = p
            if (p.step == ParkingStep.DONE && !parkingDone) {
                parkingDone = true
                score?.markParkingDone(s.tMs)
            }
        }
        perceptionAlerts.firstOrNull()?.let { alert ->
            speaker.speak(alert.text, urgent = true)
            score?.note(TripEvent.Kind.SENSOR_WARNING, s.tMs, alert.text)
            coachText = alert.text; coachPriority = Priority.WARNING; coachAt = s.tMs
        }

        val hazardAhead = n.location?.let { hazards.nearestAhead(it, n.headingDeg, maxM = 2_000.0)?.distanceM?.toInt() }
            ?: n.accidentProneAheadM

        _state.update {
            it.copy(
                phase = phase, sample = s, nav = n, lastCoachText = coachText, lastCoachPriority = coachPriority, lastCoachAtMs = coachAt,
                hazardAheadM = hazardAhead, parkingStep = parking?.step ?: ParkingStep.ALIGN, parkingPrompt = prompt,
                routeNotice = n.routeMessage,
            )
        }
        if (phase != st.phase) Log.i(TAG, "phase ${st.phase} -> $phase (gear=${s.gear}, speed=${s.speedKmh?.toInt()})")
        if (phase == Phase.REPORT) {
            result = score?.finish(s.tMs)?.let { it.copy(reviewedPractice = practiceProgress.review(it)) }
            driver = st.profile
        }
        }
        result?.let { finish(it, driver, run) }
    }

    private suspend fun finish(result: TripScore, profile: DriverProfile?, run: Long) {
        Log.i(TAG, "trip finished: score=${result.rawScore} xp=${result.xp} signalRate=${result.signalRate} events=${result.events.size}")
        var saveError: String? = null
        val updated = profile?.let {
            try {
                profileStore.applyTrip(it, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "save failed", e)
                saveError = "주행 기록을 저장하지 못했어요. 저장 공간과 프로필을 확인해 주세요"
                it
            }
        }
        currentCoroutineContext().ensureActive()
        if (saveError == null) withContext(Dispatchers.IO) { runCatching { checkpoints?.clear(result.tripId) } }
        synchronized(this) {
        if (run != generation) return
        speaker.speak(result.praise)
        // Fading feedback: tell the driver when the amount of explanation changes.
        val before = profile?.skill?.feedbackLevel ?: 3
        val after = updated?.skill?.feedbackLevel ?: before
        when {
            after < before -> speaker.speak("잘하고 있어서 이제 설명은 줄일게요. 중요한 순간만 알려 드려요")
            after > before -> speaker.speak("설명을 다시 조금 더 할게요")
        }
        if (after != before) Log.i(TAG, "feedback level $before -> $after")
        val streak = updated?.skill?.highStreak ?: 0
        when {
            result.provisional -> SignalCheck.ok("피드백단계", "관측 정보 부족으로 점수 보류(${result.rawScore}점), 단계 ${before} 유지")
            after != before -> SignalCheck.ok("피드백단계", "${before}단계 → ${after}단계 (이번 ${result.rawScore}점)")
            else -> SignalCheck.ok("피드백단계", "${after}단계 유지 (이번 ${result.rawScore}점, 90점 이상 연속 ${streak}회)")
        }
        SignalCheck.ok("주행요약", "다음 로그인 때 읽어 줄 문장: ${TripSummary.sentence(result)}")
        Log.i(TAG, "summary: ${TripSummary.sentence(result)}")
        _state.update { it.copy(phase = Phase.REPORT, tripScore = result, profile = updated, saveError = saveError) }
        memory?.sessionRunning = false
        appContext?.let { PracticeService.stop(it) }
        tickJob?.cancel(); voiceJob?.cancel(); checkpointJob?.cancel()
        }
    }

    @Synchronized private fun onVoice(cmd: VoiceCommand, run: Long) {
        if (run != generation) return
        val st = _state.value
        val now = nowMs()
        if (st.phase in setOf(Phase.IDLE, Phase.REPORT)) return
        if (cmd == VoiceCommand.HELP) { speaker.speak("다시, 경로 재탐색, 연습 종료라고 말할 수 있어요. 주차 중에는 다음으로 체크리스트를 넘겨요"); return }
        if (cmd == VoiceCommand.REROUTE) { requestReroute(now); return }
        if (cmd == VoiceCommand.STOP) { stopRequestedAt = now; speaker.speak("연습을 끝낼까요? 네 또는 아니요라고 말해 주세요"); return }
        if (cmd == VoiceCommand.NO) { stopRequestedAt = null; speaker.speak("연습을 계속할게요"); return }
        if (cmd == VoiceCommand.YES) {
            val at = stopRequestedAt
            stopRequestedAt = null
            if (at != null && now - at in 0..10_000) {
                val result = score?.checkpoint(now) ?: return
                _state.update { it.copy(phase = Phase.REPORT) }
                scope.launch { finish(result, st.profile, run) }
            }
            return
        }
        if (cmd == VoiceCommand.REPEAT && st.phase != Phase.PARKING) {
            speaker.speak(st.routeNotice ?: st.lastCoachText.ifBlank { "현재 새 안내가 없어요" }); return
        }
        if (cmd == VoiceCommand.START) { speaker.speak("코스를 선택한 뒤 기어를 D로 바꾸면 시작돼요"); return }
        val p = parking ?: return
        if (_state.value.phase != Phase.PARKING) return
        if (cmd == VoiceCommand.NEXT && st.sample.freshAt(now).speedKmh?.let { abs(it) <= 5f } != true) {
            speaker.speak("속도를 확인한 뒤 천천히 진행하세요"); return
        }
        val prompt = when (cmd) {
            VoiceCommand.NEXT -> p.next()
            VoiceCommand.REPEAT -> p.repeat()
            else -> null
        } ?: return
        speaker.speak(prompt.text)
        _state.update { it.copy(parkingStep = p.step, parkingPrompt = prompt) }
    }

    /** Called from the UI's mic button; the car's wake word is not available to third-party apps. */
    fun listen() { speaker.stop(); voice.listenOnce() }

    private fun requestReroute(now: Long) {
        if (lastRerouteAt?.let { now - it < 15_000 } == true) return
        lastRerouteAt = now
        if (!navSource.requestReroute()) speaker.speak("경로 재탐색을 요청하지 못했어요. 내비를 확인하세요")
    }

    @Synchronized fun stop() {
        generation++
        checkpointJob?.cancel()
        checkpointTripId?.let { runCatching { checkpoints?.clear(it) } }
        checkpointTripId = null
        tickJob?.cancel(); voiceJob?.cancel()
        speaker.stop()
        _state.value = SessionState()
        memory?.sessionRunning = false
        appContext?.let { PracticeService.stop(it) }
    }

    private companion object { const val TAG = "DriveSession" }
}
