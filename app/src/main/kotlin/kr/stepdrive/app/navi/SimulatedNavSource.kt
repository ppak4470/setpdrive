package kr.stepdrive.app.navi

import android.util.Log
import kr.stepdrive.app.diag.SignalCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.stepdrive.app.vehicle.VehicleSignalSource
import kr.stepdrive.core.model.LatLng
import kr.stepdrive.core.model.Maneuver
import kr.stepdrive.core.model.ManeuverType
import kr.stepdrive.core.model.NavState
import kr.stepdrive.core.model.NavStatus
import kr.stepdrive.core.model.Gear
import kr.stepdrive.core.model.freshAt

/**
 * 모의 내비: the Pleos emulator's navigation stays at one fixed position, so turn announcements, hazard warnings and
 * the arrival can't be tested on it. This source plays a scripted turn-by-turn list for the chosen course and moves
 * the car along it using the REAL vehicle speed from the emulator (Virtual Console), so the driver's own signal,
 * brake and steering are still what gets coached. It is enabled from the home screen and used for testing only.
 */
class SimulatedNavSource(private val scope: CoroutineScope, private val vehicle: VehicleSignalSource) : NavSource {
    private val _nav = MutableStateFlow(NavState())
    override val nav: StateFlow<NavState> = _nav.asStateFlow()
    private var job: Job? = null

    /** One scripted step: a maneuver [atM] metres from the start. */
    private data class Step(val type: ManeuverType, val atM: Double, val description: String)

    override fun start() = Unit
    override fun stop() { job?.cancel(); _nav.value = NavState() }

    override fun requestRoute(destination: Destination, waypoints: List<Destination>): Boolean {
        job?.cancel()
        val steps = scriptFor(destination)
        val total = steps.last().atM
        val start = LatLng(37.5410, 127.0450)
        val hazardAt = 900.0        // distance at which the course passes the scripted accident-prone point
        var travelled = 0.0
        var lastT = System.currentTimeMillis()
        _nav.value = NavState(NavStatus.ROUTE_DRIVE, remaining(steps, 0.0), total.toInt(), (total / 8).toInt(), start, 45.0, 50, null)
        Log.i(TAG, "simulated route to ${destination.name}: ${steps.size} steps, ${total.toInt()} m")
        SignalCheck.ok("모의내비", "경로 시작, ${total.toInt()}m · 속도가 0이면 진행되지 않음")
        var lastLogged = -1
        job = scope.launch {
            while (travelled < total) {
                delay(500)
                val now = System.currentTimeMillis()
                val sample = vehicle.samples.value.freshAt(now)
                val v = if (sample.gear == Gear.DRIVE) (sample.speedKmh ?: 0f).coerceAtLeast(0f) / 3.6 else 0.0
                travelled += v * (now - lastT) / 1000.0
                lastT = now
                val ahead = hazardAt - travelled
                val pct = (travelled / total * 10).toInt()
                if (pct != lastLogged && pct > 0) { lastLogged = pct; SignalCheck.ok("모의내비", "진행 ${(travelled).toInt()}m / ${total.toInt()}m, 다음 ${remaining(steps, travelled).firstOrNull()?.let { m -> "${m.type} ${m.distanceM.toInt()}m" } ?: "-"}") }
                val remain = (total - travelled).coerceAtLeast(0.0)
                // Position drifts north-east with distance so the hazard index can be exercised too.
                val pos = LatLng(start.lat + travelled / 111_000.0 * 0.7, start.lng + travelled / 88_000.0 * 0.7)
                _nav.update {
                    it.copy(
                        status = NavStatus.ROUTE_DRIVE, maneuvers = remaining(steps, travelled),
                        remainDistanceM = remain.toInt(), remainTimeS = (remain / 8).toInt(),
                        location = pos, headingDeg = 45.0, speedLimitKmh = if (travelled > 1_600) 80 else 50,
                        accidentProneAheadM = if (ahead in 0.0..600.0) ahead.toInt() else null,
                    )
                }
            }
            _nav.update { it.copy(maneuvers = listOf(Maneuver(ManeuverType.ARRIVE, 0.0, destination.name)), remainDistanceM = 0, remainTimeS = 0) }
            Log.i(TAG, "simulated route: arrived")
            SignalCheck.ok("모의내비", "도착 · 전체 진행 완료")
        }
        return true
    }

    private fun remaining(steps: List<Step>, travelled: Double): List<Maneuver> =
        steps.filter { it.atM - travelled > -5 }.map { Maneuver(it.type, (it.atM - travelled).coerceAtLeast(0.0), it.description) }

    /** Right turn → accident-prone crossing → two turns in a row → merge → exit → arrive. ~3.2 km. */
    private fun scriptFor(d: Destination) = listOf(
        Step(ManeuverType.RIGHT, 420.0, "성수이로 방면"),
        Step(ManeuverType.LEFT, 1_250.0, "뚝섬로 방면"),
        Step(ManeuverType.RIGHT, 1_400.0, "서울숲길 방면"),
        Step(ManeuverType.MERGE_RIGHT, 1_900.0, "강변북로 합류"),
        Step(ManeuverType.EXIT_RIGHT, 2_700.0, "영동대교 출구"),
        Step(ManeuverType.ARRIVE, 3_200.0, d.name),
    )

    private companion object { const val TAG = "SimNav" }
}

/** Real navigation by default; simulation never sends route requests to the real navigator. */
class SwitchableNavSource(private val real: NavSource, private val sim: SimulatedNavSource, scope: CoroutineScope) : NavSource {
    private val _useSim = MutableStateFlow(false)
    val useSim: StateFlow<Boolean> = _useSim.asStateFlow()
    private val _nav = MutableStateFlow(NavState())
    override val nav: StateFlow<NavState> = _nav.asStateFlow()
    override val recentDestinations: StateFlow<List<Destination>> get() = real.recentDestinations
    override val currentDestination: StateFlow<Destination?> get() = real.currentDestination
    override fun openSearch(keyword: String): Boolean = real.openSearch(keyword)

    init {
        scope.launch { real.nav.collect { if (!_useSim.value) _nav.value = it } }
        scope.launch { sim.nav.collect { if (_useSim.value) _nav.value = it } }
    }

    fun setSimulated(on: Boolean) { _useSim.value = on; _nav.value = if (on) sim.nav.value else real.nav.value }
    override fun start() = real.start()
    override fun stop() { real.stop(); sim.stop() }
    override fun refreshDestinations() = real.refreshDestinations()
    override fun requestReroute(): Boolean = if (_useSim.value) false else real.requestReroute()
    override fun requestRoute(destination: Destination, waypoints: List<Destination>): Boolean {
        return if (_useSim.value) sim.requestRoute(destination, waypoints) else real.requestRoute(destination, waypoints)
    }
}
