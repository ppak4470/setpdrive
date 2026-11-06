package kr.stepdrive.app.navi

import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.constants.NaviErrorCode
import kr.stepdrive.core.navigation.RouteProgress
import kr.stepdrive.core.navigation.RoutePhase
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import kr.stepdrive.app.diag.SignalCheck
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kr.stepdrive.core.model.LatLng
import kr.stepdrive.core.model.Maneuver
import kr.stepdrive.core.model.ManeuverType
import kr.stepdrive.core.model.NavState
import kr.stepdrive.core.model.NavStatus
import kr.stepdrive.core.model.freshAt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pleos NaviHelper SDK adapter: turn-by-turn list, remaining distance/time, current position and heading,
 * road speed limit, and the navigation's own accident-prone caution points.
 */
class PleosNavSource(context: Context, private val scope: CoroutineScope) : NavSource {
    private val _nav = MutableStateFlow(NavState())
    override val nav: StateFlow<NavState> = _nav.asStateFlow()
    private val progress = RouteProgress()
    private var generation = 0L
    private var lastResponseAt = 0L
    private var listener: NaviHelperEventListener? = null
    @Synchronized private fun update(block: (NavState) -> NavState) { _nav.value = progress.update(block) }
    @Synchronized private fun evolve(block: (RouteProgress) -> NavState) { _nav.value = block(progress) }
    @Synchronized private fun accept(run: Long): Boolean {
        if (!started || run != generation) return false
        lastResponseAt = System.currentTimeMillis()
        return true
    }
    private val helper = NaviHelper(context.applicationContext)
    @Volatile private var started = false
    @Volatile private var connected = false
    @Volatile private var initializing = false
    private var connectStartedAt = 0L
    private var pollJob: Job? = null
    private val _recent = MutableStateFlow<List<Destination>>(emptyList())
    override val recentDestinations: StateFlow<List<Destination>> = _recent.asStateFlow()
    private var recentList: List<Destination> = emptyList()
    private val _destination = MutableStateFlow<Destination?>(null)
    override val currentDestination: StateFlow<Destination?> = _destination.asStateFlow()
    private var bookmarkList: List<Destination> = emptyList()

    private fun listenerFor(run: Long) = object : NaviHelperEventListener {
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) { if (!accept(run)) return;
            if (!started) return
            tbtInfoList.firstOrNull()?.let { Log.i(TAG, "tbt: ${it.type} ${it.distance.toInt()}m ${it.description}"); SignalCheck.ok("내비안내", "${it.type} ${it.distance.toInt()}m") }
            evolve { it.guidance(tbtInfoList.map { t -> Maneuver(t.type.toManeuverType(), t.distance, t.description) }, System.currentTimeMillis()) }
        }
        override fun onDrivingInfo(drivingInfo: DrivingInfo) { if (!accept(run)) return;
            update { it.copy(remainDistanceM = drivingInfo.destination.distance, remainTimeS = drivingInfo.destination.duration) }
        }
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) { if (!accept(run)) return;
            if (!started) return
            update { it.copy(location = LatLng(currentLocationInfo.latitude, currentLocationInfo.longitude), headingDeg = currentLocationInfo.heading, locationTMs = System.currentTimeMillis()) }
        }
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) { if (!accept(run)) return;
            update { it.copy(speedLimitKmh = speedLimit.takeIf { l -> l > 0 }) }
        }
        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) { if (!accept(run)) return;
            val ahead = cautionPointList.filter { it.type == CautionPointType.ACCIDENT_PRONE_AREA }.minOfOrNull { it.remainDistance }
            update { it.copy(accidentProneAheadM = ahead) }
        }
        override fun onNavigationStatus(status: NavigationStatus) { if (!accept(run)) return;
            if (!started) return
            evolve { it.status(when (status) {
                NavigationStatus.ROUTE_DRIVE -> NavStatus.ROUTE_DRIVE
                NavigationStatus.FREE_DRIVE -> NavStatus.FREE_DRIVE
                else -> NavStatus.INACTIVE
            }, System.currentTimeMillis()) }
        }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) { if (!accept(run)) return;
            update { it.copy(maneuvers = listOf(Maneuver(ManeuverType.ARRIVE, 0.0, destinationArrivedInfo.name)), remainDistanceM = 0, remainTimeS = 0, routePhase = RoutePhase.ARRIVED, routeMessage = null, guidanceTMs = System.currentTimeMillis()) }
        }
        override fun onRouteCancelled() { if (!accept(run)) return; evolve { it.clear(RoutePhase.IDLE, System.currentTimeMillis(), "경로 안내가 취소됐어요") } }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) { if (!accept(run)) return; evolve { it.begin(System.currentTimeMillis()) } }
        override fun onRoutingCompleted() { if (!accept(run)) return; freshGuidance() }
        override fun onReRouteEnded() { if (!accept(run)) return; freshGuidance() }
        override fun onError(errorCode: NaviErrorCode) {
            if (!accept(run)) return
            evolve { it.clear(RoutePhase.FAILED, System.currentTimeMillis(), "경로를 찾지 못했어요. 내비를 확인해 주세요") }
        }
        override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) { if (!accept(run)) return;
            recentList = recentDestinationInfoList.map { Destination(it.name, LatLng(it.latitude, it.longitude), it.address) }
            Log.i(TAG, "recent destinations: ${recentList.size}"); SignalCheck.ok("목적지", "최근 목적지 ${recentList.size}곳")
            publishDestinations()
        }
        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) { if (!accept(run)) return;
            bookmarkList = bookmarkInfoList.map { Destination(it.name, LatLng(it.latitude, it.longitude), it.address) }
            Log.i(TAG, "bookmarks: ${bookmarkList.size}")
            publishDestinations()
        }
        override fun onBookMarkChanged() { if (!accept(run)) return; refreshDestinations() }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) { if (!accept(run)) return;
            val d = Destination(destinationInfo.name, LatLng(destinationInfo.latitude, destinationInfo.longitude), destinationInfo.address)
            if (d.name.isBlank()) return
            Log.i(TAG, "destination on navi: ${d.name} (${d.position.lat}, ${d.position.lng})")
            SignalCheck.ok("목적지", "내비 목적지 ${d.name}")
            _destination.value = d
        }
        override fun onDestinationChanged() { if (!accept(run)) return; Log.i(TAG, "destination changed"); runCatching { helper.getDestinationInfo() }; refreshDestinations() }
    }

    private fun freshGuidance() {
        if (_nav.value.routePhase != RoutePhase.GUIDING) evolve { it.complete(System.currentTimeMillis()) }
        runCatching { helper.getTBTInfo(); helper.getRouteStateInfo() }.onFailure { connectionFailed(it) }
    }

    override fun requestReroute(): Boolean {
        if (!started || !connected || _nav.value.status != NavStatus.ROUTE_DRIVE) return false
        return runCatching {
        evolve { it.begin(System.currentTimeMillis()) }
        helper.requestReRoute()
        true
        }.getOrElse { evolve { it.clear(RoutePhase.FAILED, System.currentTimeMillis(), "경로 재탐색을 요청하지 못했어요") }; false }
    }

    override fun openSearch(keyword: String): Boolean = runCatching {
        check(started && connected) { "Navigation is not connected" }
        helper.requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword))
        Log.i(TAG, "opened navi search: $keyword")
        true
    }.onFailure { Log.w(TAG, "openSearch failed: $it"); SignalCheck.fail("목적지", "내비 검색 화면 열기 실패: $it") }.getOrDefault(false)

    private fun publishDestinations() {
        // Bookmarks first (home/work), then recent places; same name+position only once.
        _recent.value = (bookmarkList + recentList).filter { it.name.isNotBlank() }.distinctBy { it.name to it.position }
    }

    override fun refreshDestinations() {
        if (!started) return
        if (!connected) { connect(); return }
        runCatching { helper.getRecentDestinationInfo(); helper.getBookmarkInfo(); helper.getDestinationInfo() }.onFailure { Log.w(TAG, "destinations unavailable: $it") }
    }

    override fun start() {
        if (started) return
        started = true
        connect()
        pollJob = scope.launch {
            while (isActive) {
                delay(2_000)
                evolve { it.expire(System.currentTimeMillis()) }
                if (connected && System.currentTimeMillis() - lastResponseAt > 15_000) connectionFailed(IllegalStateException("Navigation response timeout"))
                if (!connected) connect()
                else runCatching { helper.getRouteStateInfo(); helper.getCurrentLocationInfo(); helper.getTBTInfo() }
                    .onFailure { connectionFailed(it) }
            }
        }
    }

    private fun connectionFailed(error: Throwable) {
        connected = false; initializing = false; generation++
        evolve { it.clear(RoutePhase.DISCONNECTED, System.currentTimeMillis(), "내비 연결을 다시 확인하고 있어요") }
        SignalCheck.fail("내비연결", error.toString())
    }

    @Synchronized private fun connect() {
        if (!started || connected) return
        if (initializing && System.currentTimeMillis() - connectStartedAt < 10_000) return
        initializing = true; connectStartedAt = System.currentTimeMillis()
        val run = ++generation
        lastResponseAt = connectStartedAt
        runCatching {
            runCatching { listener?.let { helper.removeListener(it) }; helper.release() }
            listener = listenerFor(run)
            helper.addListener(listener!!)
            helper.initialize(
                onConnected = {
                    if (!started || run != generation) return@initialize
                    connected = true; initializing = false
                    lastResponseAt = System.currentTimeMillis()
                    evolve { it.complete(lastResponseAt) }
                    Log.i(TAG, "NaviHelper connected")
                    SignalCheck.ok("내비연결", "NaviHelper 연결")
                    SignalCheck.waiting("내비안내", "앱에서 연습 코스를 선택해 출발하면 안내가 들어옵니다")
                    SignalCheck.waiting("경로요청", "앱에서 연습 코스를 선택해 출발해 보세요")
                    runCatching { helper.getRouteStateInfo(); helper.getCurrentLocationInfo(); helper.getTBTInfo() }
                    SignalCheck.waiting("목적지", "내비에 최근 목적지나 즐겨찾기가 있으면 코스 후보로 씁니다")
                    refreshDestinations()
                },
                onFailed = { if (run == generation) connectionFailed(it) },
            )
        }.onFailure { Log.w(TAG, "NaviHelper unavailable: $it"); connectionFailed(it) }
    }

    override fun stop() {
        if (!started) return
        started = false; generation++
        connected = false; initializing = false
        pollJob?.cancel(); pollJob = null
        evolve { it.clear(RoutePhase.DISCONNECTED, System.currentTimeMillis(), "내비 연결이 중지됐어요") }
        runCatching { listener?.let { helper.removeListener(it) }; helper.release() }
    }

    override fun requestRoute(destination: Destination, waypoints: List<Destination>): Boolean = runCatching {
        check(started && connected) { "Navigation is not connected" }
        evolve { it.begin(System.currentTimeMillis()) }
        val dest = RouteInfo(
            latitude = destination.position.lat, longitude = destination.position.lng,
            poiName = destination.name, poiId = "", address = destination.address, poiSubId = "",
        )
        if (waypoints.isEmpty()) {
            helper.requestRoute(dest)
        } else {
            // The SDK addresses waypoints as FIRST/SECOND: at most two detour points per course.
            val wps = waypoints.take(2).mapIndexed { i, w ->
                RequestWaypointInfo(
                    latitude = w.position.lat, longitude = w.position.lng, poiName = w.name,
                    waypointIndex = if (i == 0) WaypointIndex.FIRST else WaypointIndex.SECOND,
                    poiId = "", address = w.address, poiSubId = "",
                )
            }
            helper.requestRouteWithWaypoints(RouteWithWaypointsRequest(dest, wps))
        }
        true
    }.onSuccess { SignalCheck.ok("경로요청", "${destination.name} 경로 요청 성공") }.onFailure { evolve { it.clear(RoutePhase.FAILED, System.currentTimeMillis(), "경로 요청에 실패했어요") }; SignalCheck.fail("경로요청", "요청 실패") }.getOrDefault(false)

    private companion object { const val TAG = "PleosNav" }
}

/** Preserve directional exits; the SDK's central exit does not establish a right turn. */
internal fun TurnType.toManeuverType(): ManeuverType = when (this) {
        TurnType.TURN_LEFT, TurnType.TURN_SHARP_L -> ManeuverType.LEFT
        TurnType.TURN_RIGHT, TurnType.TURN_SHARP_R -> ManeuverType.RIGHT
        TurnType.TURN_KEEP_L, TurnType.TURN_ENTER_IC_L, TurnType.TURN_ENTER_UNDER_L, TurnType.TURN_ENTER_OVER_L -> ManeuverType.MERGE_LEFT
        TurnType.TURN_KEEP_R, TurnType.TURN_ENTER_IC_R, TurnType.TURN_ENTER_UNDER_R, TurnType.TURN_ENTER_OVER_R -> ManeuverType.MERGE_RIGHT
        TurnType.TURN_EXIT_IC_R -> ManeuverType.EXIT_RIGHT
        TurnType.TURN_EXIT_IC_L -> ManeuverType.EXIT_LEFT
        TurnType.TURN_EXIT_IC_C -> ManeuverType.EXIT
        TurnType.TURN_UTURN -> ManeuverType.UTURN
        TurnType.TURN_ARRIVE -> ManeuverType.ARRIVE
        TurnType.TURN_STRAIGHT -> ManeuverType.STRAIGHT
        else -> ManeuverType.OTHER
    }

