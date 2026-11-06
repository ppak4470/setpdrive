package kr.stepdrive.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kr.stepdrive.core.safety.DrivingPolicy
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kr.stepdrive.app.di.AppContainer
import kr.stepdrive.app.di.RuntimeMode
import android.util.Log
import kr.stepdrive.app.navi.Destination
import kr.stepdrive.app.session.Phase
import kr.stepdrive.app.session.SessionState
import kr.stepdrive.core.course.CourseRecommender
import kr.stepdrive.core.demo.DemoScenario
import kr.stepdrive.core.hazard.HazardIndex
import kr.stepdrive.core.model.CourseCandidate
import kr.stepdrive.core.model.EvaluatedCourse
import kr.stepdrive.core.model.LatLng
import kr.stepdrive.core.model.RouteFeatures
import kr.stepdrive.core.model.Tier
import kr.stepdrive.core.report.TripSummary
import kotlin.math.abs
import kr.stepdrive.app.diag.SignalCheck
import kr.stepdrive.core.profile.DriverProfile

enum class Screen { PROFILE, HOME, COURSE, DRIVE, REPORT }

data class UiState(
    val screen: Screen = Screen.PROFILE,
    val mode: RuntimeMode = RuntimeMode.DEMO,
    val profiles: List<DriverProfile> = emptyList(),
    val active: DriverProfile? = null,
    val courses: List<EvaluatedCourse> = emptyList(),
    val restricted: Boolean = true,
    val demoPlaying: Boolean = false,
    val message: String? = null,
    /** 모의 내비 (emulator testing): scripted turn list moved by the real vehicle speed. */
    val simNav: Boolean = false,
    /** Where the course candidates came from: navigation destinations or the built-in sample. */
    val courseSource: String = "",
    /** Set after the driver typed a place: the next destination the navigation reports becomes the course. */
    val awaitingSearch: Boolean = false,
    val hazardStatus: String = "내비 사고구간 안내 사용",
    val dataSettings: Boolean = false,
    val dataBusy: Boolean = false,
    val recoveryNotice: String? = null,
    val sensorStatus: String = "차로·장애물 센서 미연결",
)

class MainViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(UiState(mode = c.mode))
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    val session: StateFlow<SessionState> = c.session.state
    private var hazardIndex: HazardIndex = HazardIndex(emptyList())

    init {
        restoreWhereWeWere()
        viewModelScope.launch {
            try { c.profileStore.purgeExpired() } catch (e: Exception) { storageFailure(e) }
            c.profileStore.profiles.catch { storageFailure(it) }.collect { list ->
                val activeId = _ui.value.active?.id
                if (activeId != null && list.none { it.id == activeId }) {
                    c.session.stop()
                    c.memory.activeProfileId = null
                }
                _ui.update { st ->
                    // Restore the unlocked driver after the activity (or the process) was recreated.
                    val remembered = if (st.active == null && c.memory.stillUnlocked()) list.firstOrNull { it.id == c.memory.activeProfileId } else null
                    val active = list.firstOrNull { it.id == st.active?.id } ?: remembered
                    val screen = if (active == null) Screen.PROFILE else if (st.screen == Screen.PROFILE) Screen.HOME else st.screen
                    if (remembered != null) Log.i(TAG, "restored driver ${remembered.name} (unlocked ${(System.currentTimeMillis() - c.memory.unlockedAtMs) / 60000} min ago)")
                    st.copy(profiles = list, active = active, screen = screen)
                }
            }
        }
        viewModelScope.launch { combine(c.uxRestrictions.restricted, c.vehicleSource.samples) { r, s -> DrivingPolicy.restricted(r, s) }
            .collect { r -> _ui.update { it.copy(restricted = r, dataSettings = it.dataSettings && !r) } } }
        viewModelScope.launch { c.hazardRepository.status.collect { text -> _ui.update { it.copy(hazardStatus = text) } } }
        viewModelScope.launch { c.recoveryNotice.collect { text -> _ui.update { it.copy(recoveryNotice = text, sensorStatus = c.perception.availability) } } }
        viewModelScope.launch { c.demoClock.playing.collect { p -> _ui.update { it.copy(demoPlaying = p) } } }
        viewModelScope.launch {
            c.session.state.collect { s ->
                when (s.phase) {
                    Phase.READY, Phase.DRIVING, Phase.PARKING -> _ui.update { it.copy(screen = Screen.DRIVE, active = it.active ?: s.profile) }
                    Phase.REPORT -> _ui.update { it.copy(screen = Screen.REPORT, active = s.profile ?: it.active, message = s.saveError) }
                    else -> Unit
                }
            }
        }
        viewModelScope.launch { hazardIndex = c.hazardRepository.index(c.mode == RuntimeMode.DEMO) }
        if (c.mode == RuntimeMode.PLEOS) {
            SignalCheck.waiting("코스후보", "홈에서 「연습 코스 만들기」를 누르면 확인됩니다")
            SignalCheck.waiting("모의내비", "홈 화면에서 「모의 내비 켜기」를 누르세요")
            SignalCheck.waiting("알림서비스", "코스를 선택해 출발하면 상단 알림이 떠야 합니다")
            SignalCheck.waiting("회전코칭", "모의 내비를 켜고 출발하면 약 30초 뒤 우회전 안내가 나옵니다")
            SignalCheck.waiting("참고안내", "속도 50에서 깜빡이 없이 핸들을 120도 이상 돌리면 나옵니다")
            SignalCheck.waiting("주차체크", "기어 R로 바꾸면 1단계부터 진행됩니다")
            SignalCheck.waiting("기어안내", "속도 5인 채 R↔D를 바꾸면 나옵니다")
            SignalCheck.waiting("피드백단계", "90점 이상 주행 2회 연속이면 단계가 내려갑니다")
            SignalCheck.waiting("주행요약", "주행을 한 번 끝낸 뒤 운전자를 다시 선택하면 요약이 나옵니다")
        }
        c.switchableNav?.let { sw -> viewModelScope.launch { sw.useSim.collect { on -> _ui.update { it.copy(simNav = on) } } } }
        // When the driver picks a place in the navigation's search, the destination comes back here → rebuild courses.
        viewModelScope.launch {
            c.navSource.currentDestination.collect { d ->
                if (d != null && _ui.value.awaitingSearch) { _ui.update { it.copy(awaitingSearch = false, message = null) }; buildCourses() }
            }
        }
    }

    /** Typed destination: open the car navigation's search with the keyword; the driver picks the result there. */
    fun searchDestination(keyword: String) {
        if (!requireParked()) return
        val k = keyword.trim()
        if (k.isEmpty()) return
        val opened = c.navSource.openSearch(k)
        if (opened) {
            _ui.update { it.copy(awaitingSearch = true, message = "내비에서 '$k' 검색 결과를 고르세요. 고르면 코스가 만들어져요") }
            SignalCheck.ok("목적지", "내비 검색 열림: $k · 내비에서 장소를 고르면 코스 후보가 됩니다")
        } else {
            _ui.update { it.copy(message = "이 환경에서는 내비 검색을 열 수 없어요. 내비 앱에서 목적지를 먼저 정해 주세요") }
        }
    }

    /** Activity resumed (e.g. back from the navigation): pick up a destination chosen there. */
    fun onResume() {
        c.navSource.refreshDestinations()
        if (_ui.value.screen == Screen.COURSE && !_ui.value.awaitingSearch) buildCourses()
    }

    /**
     * The activity was recreated (the navigation came in front, the system rebuilt us, or the driver re-opened the app).
     * If the practice session is still running in the application container, go straight back to the drive screen.
     */
    private fun restoreWhereWeWere() {
        val s = c.session.state.value
        if (s.phase != Phase.IDLE) {
            Log.i(TAG, "activity recreated during session: phase=${s.phase}, back to the drive screen")
            SignalCheck.ok("세션유지", "앱을 다시 열었을 때 주행 화면 복귀 (단계 ${s.phase})")
            _ui.update { it.copy(screen = if (s.phase == Phase.REPORT) Screen.REPORT else Screen.DRIVE, active = s.profile) }
        } else if (c.memory.stillUnlocked()) {
            // Profiles arrive asynchronously; the collector above picks the remembered driver up.
            Log.i(TAG, "no running session; driver ${c.memory.activeProfileId} still unlocked")
        }
    }

    /** Last-trip recap (home screen + spoken once at unlock). */
    fun lastSummary(p: DriverProfile?): String? = p?.trips?.lastOrNull()?.let { TripSummary.sentence(it) }

    fun setSimNav(on: Boolean) {
        if (!requireParked()) return
        c.switchableNav?.setSimulated(on)
        if (on) SignalCheck.waiting("모의내비", "켜짐. 코스를 선택해 출발하고 Virtual Console 속도를 30~40으로 두세요") else SignalCheck.reset("모의내비", "홈 화면에서 「모의 내비 켜기」를 누르세요")
    }

    private fun requireParked(): Boolean {
        if (!c.recoveryReady.value) { _ui.update { it.copy(message = "이전 연습 기록을 확인하고 있어요") }; return false }
        if (!DrivingPolicy.restricted(c.uxRestrictions.restricted.value, c.vehicleSource.samples.value, System.currentTimeMillis())) return true
        _ui.update { it.copy(message = "안전하게 정차한 뒤 이용해 주세요") }
        return false
    }

    // ----- profiles -----
    fun createProfile(name: String, pin: String) {
        if (!requireParked()) return
        if (name.isBlank() || pin.length < 4) { _ui.update { it.copy(message = "이름과 4자리 이상 PIN을 입력하세요") }; return }
        viewModelScope.launch {
            val p = try { c.profileStore.create(name, pin) } catch (e: Exception) { storageFailure(e); return@launch }
            c.memory.activeProfileId = p.id; c.memory.unlockedAtMs = System.currentTimeMillis()
            _ui.update { it.copy(active = p, screen = Screen.HOME, message = null) }
            c.speaker.speak("${p.name}님, 반가워요. 첫 주행으로 시작 난이도를 정해 볼게요")
        }
    }

    fun unlock(profile: DriverProfile, pin: String) {
        if (!requireParked()) return
        if (!c.profileStore.verifyPin(profile, pin)) { _ui.update { it.copy(message = "PIN이 맞지 않아요") }; return }
        viewModelScope.launch {
            val p = try { c.profileStore.touch(profile) } catch (e: Exception) { storageFailure(e); return@launch }
            c.memory.activeProfileId = p.id; c.memory.unlockedAtMs = System.currentTimeMillis()
            _ui.update { it.copy(active = p, screen = Screen.HOME, message = null) }
            lastSummary(p)?.let { c.speaker.speak(it); SignalCheck.ok("주행요약", it) } ?: SignalCheck.waiting("주행요약", "주행을 한 번 끝낸 뒤 운전자를 다시 선택하면 요약이 나옵니다")
        }
    }

    fun lock() { if (!requireParked()) return; c.session.stop(); c.simNav.stop(); c.memory.activeProfileId = null; _ui.update { it.copy(active = null, screen = Screen.PROFILE) } }

    fun deleteProfile(profile: DriverProfile) {
        if (!requireParked()) return
        viewModelScope.launch { try { c.profileStore.delete(profile.id) } catch (e: Exception) { storageFailure(e) } }
    }

    private fun storageFailure(error: Throwable) {
        if (error is CancellationException) throw error
        Log.w(TAG, "profile storage failed", error)
        _ui.update { it.copy(message = "운전자 기록을 읽거나 저장하지 못했어요. 저장 공간을 확인한 뒤 다시 시도해 주세요") }
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    fun showDataSettings() { if (requireParked()) _ui.update { it.copy(dataSettings = true, message = null) } }
    fun closeDataSettings() = _ui.update { it.copy(dataSettings = false) }
    fun refreshPublicData(key: String, year: String, municipality: String) {
        if (!requireParked() || _ui.value.dataBusy) return
        val y = year.toIntOrNull() ?: return
        _ui.update { it.copy(dataBusy = true, message = null) }
        viewModelScope.launch {
            val result = c.hazardRepository.refresh(key, y, municipality.trim())
            hazardIndex = c.hazardRepository.index(c.mode == RuntimeMode.DEMO || _ui.value.simNav)
            _ui.update { it.copy(dataBusy = false, message = result.fold({ count -> "${count}곳을 갱신했어요. 통계에 없는 위험은 별도로 확인해야 해요" }, { error -> error.message })) }
        }
    }

    // ----- courses -----
    fun buildCourses() {
        if (!requireParked()) return
        val profile = _ui.value.active ?: return
        viewModelScope.launch {
            hazardIndex = c.hazardRepository.index(c.mode == RuntimeMode.DEMO || _ui.value.simNav)
            // Live mode only lists actual navigation destinations. Samples require explicit simulation.
            c.navSource.refreshDestinations()
            // The destination set on the navigation (the driver's own choice) comes first, then bookmarks/recent places.
            val current = c.navSource.currentDestination.value
            val recent = (listOfNotNull(current) + c.navSource.recentDestinations.value).distinctBy { it.name to it.position }
            val fromNav = recent.take(3).mapIndexed { i, d -> candidateFor(i, d) }
            val candidates = if (c.mode == RuntimeMode.DEMO || _ui.value.simNav) DemoScenario.courseCandidates else fromNav
            val evaluated = CourseRecommender(hazardIndex).evaluate(candidates, profile.skill.unlockedTier())
            if (!requireParked()) return@launch
            val open = evaluated.count { it.tier <= profile.skill.unlockedTier() }
            if (fromNav.isEmpty()) SignalCheck.waiting("코스후보", "내비 목적지 없음 · 검색으로 목적지를 선택하세요")
            else SignalCheck.ok("코스후보", "내비 목적지 ${recent.size}곳 → 후보 ${evaluated.size}개 (열림 ${open}개, 추천 ${evaluated.count { it.recommended }}개)")
            if (open == 0) SignalCheck.fail("코스후보", "열린 코스가 없음")
            _ui.update { it.copy(courses = evaluated, screen = Screen.COURSE, courseSource = if (c.mode == RuntimeMode.DEMO || it.simNav) "모의 코스" else "내비 목적지 ${fromNav.size}곳") }
        }
    }

    /** A destination is not an alternate route. Details stay unknown until supplied by navigation. */
    private fun candidateFor(i: Int, d: Destination) = CourseCandidate(
        id = "nav-$i", name = d.name, destinationName = d.name, distanceM = 0, durationS = 0,
        polyline = listOf(d.position), features = RouteFeatures(), tierOverride = Tier.CITY, detailsVerified = false,
    )

    fun startCourse(course: EvaluatedCourse) {
        if (!requireParked()) return
        val profile = _ui.value.active ?: return
        val pts = course.candidate.polyline
        val destination = Destination(course.candidate.destinationName, pts.last())
        // Interior polyline points become detour waypoints (max two: the SDK addresses FIRST/SECOND).
        val waypoints = if (course.hazardsOnRoute.isEmpty() && pts.size > 2) pts.subList(1, pts.size - 1).take(2).mapIndexed { i, p -> Destination("경유지 ${i + 1}", p) } else emptyList()
        if (!c.navSource.requestRoute(destination, waypoints)) {
            _ui.update { it.copy(message = "내비 경로 요청에 실패했어요. 내비 연결과 목적지를 확인해 주세요") }
            return
        }
        c.session.start(profile, course.tier, hazardIndex)
        c.speaker.speak("${course.candidate.destinationName}까지 연습 코스로 출발할게요")
        _ui.update { it.copy(screen = Screen.DRIVE) }
    }

    fun goHome() { if (!requireParked()) return; c.session.stop(); c.simNav.stop(); _ui.update { it.copy(screen = Screen.HOME) } }

    fun listen() = c.session.listen()

    // ----- demo controls (emulator / 2nd-round presentation) -----
    fun demoPlay() = c.demoClock.play()
    fun demoPause() = c.demoClock.pause()
    fun demoRestart() = c.demoClock.restart()

    companion object {
        private const val TAG = "StepDrive"
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(container) as T
        }
    }
}
