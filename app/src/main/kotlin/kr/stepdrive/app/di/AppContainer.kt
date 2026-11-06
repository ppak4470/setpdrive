package kr.stepdrive.app.di

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kr.stepdrive.app.data.SessionCheckpointStore
import kr.stepdrive.app.safety.UnavailablePerceptionSource
import java.io.File
import kr.stepdrive.app.BuildConfig
import kr.stepdrive.app.data.ProfileStore
import kr.stepdrive.app.data.SessionMemory
import kr.stepdrive.app.demo.DemoClock
import kr.stepdrive.app.diag.SignalCheck
import kr.stepdrive.app.driving.CarUxRestrictionsMonitor
import kr.stepdrive.app.driving.DemoUxRestrictionsMonitor
import kr.stepdrive.app.driving.UxRestrictionsMonitor
import kr.stepdrive.app.hazard.HazardRepository
import kr.stepdrive.app.location.LocationSource
import kr.stepdrive.app.location.NoLocationSource
import kr.stepdrive.app.location.PleosLocationSource
import kr.stepdrive.app.navi.DemoNavSource
import kr.stepdrive.app.navi.NavSource
import kr.stepdrive.app.navi.PleosNavSource
import kr.stepdrive.app.navi.SimulatedNavSource
import kr.stepdrive.app.navi.SwitchableNavSource
import kr.stepdrive.app.session.DriveSessionController
import kr.stepdrive.app.speech.AndroidSpeaker
import kr.stepdrive.app.speech.NoVoiceCommandSource
import kr.stepdrive.app.speech.FallbackSpeaker
import kr.stepdrive.app.speech.PleosVoiceCommandSource
import kr.stepdrive.app.speech.Speaker
import kr.stepdrive.app.speech.VoiceCommandSource
import kr.stepdrive.app.vehicle.DemoVehicleSource
import kr.stepdrive.app.vehicle.PleosVehicleSource
import kr.stepdrive.app.vehicle.VehicleSignalSource

enum class RuntimeMode { PLEOS, DEMO }

/**
 * Hand-rolled dependency wiring. Chooses real Pleos SDK adapters when the Pleos vehicle service is
 * installed (car or Pleos Connect emulator), and scripted demo sources otherwise.
 */
class AppContainer(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val mode: RuntimeMode = detectMode(app)

    val hazardRepository = HazardRepository(app)
    val profileStore = ProfileStore(app)
    val memory = SessionMemory(app)
    val checkpoints = SessionCheckpointStore(File(app.filesDir, "session-checkpoint.json"))
    val recoveryReady = MutableStateFlow(false)
    val recoveryNotice = MutableStateFlow<String?>(null)
    val perception = UnavailablePerceptionSource()
    val demoClock = DemoClock(scope)

    val vehicleSource: VehicleSignalSource =
        if (mode == RuntimeMode.PLEOS) PleosVehicleSource(app, scope) else DemoVehicleSource(demoClock)
    /** 모의 내비 for the emulator, whose navigation never moves; switched from the home screen. */
    val simNav: SimulatedNavSource = SimulatedNavSource(scope, vehicleSource)
    val switchableNav: SwitchableNavSource? = if (mode == RuntimeMode.PLEOS) SwitchableNavSource(PleosNavSource(app, scope), simNav, scope) else null
    val navSource: NavSource = switchableNav ?: DemoNavSource(demoClock)
    val locationSource: LocationSource =
        if (mode == RuntimeMode.PLEOS) PleosLocationSource(app) else NoLocationSource()
    val speaker: Speaker =
        if (mode == RuntimeMode.PLEOS) FallbackSpeaker(app) else AndroidSpeaker(app)
    val voiceCommands: VoiceCommandSource =
        if (mode == RuntimeMode.PLEOS) PleosVoiceCommandSource(app) else NoVoiceCommandSource()
    val uxRestrictions: UxRestrictionsMonitor =
        if (mode == RuntimeMode.PLEOS) CarUxRestrictionsMonitor(app) else DemoUxRestrictionsMonitor(vehicleSource)

    val session = DriveSessionController(scope, vehicleSource, navSource, locationSource, speaker, voiceCommands, profileStore, app, memory, checkpoints, perception)

    init {
        Log.i(TAG, "runtime mode = $mode (process start, pid=${android.os.Process.myPid()})")
        // If the process restarted while a session was running, the system killed us: report it, the tester can't see that otherwise.
        if (memory.sessionRunning) { SignalCheck.fail("세션유지", "연습 중 앱 프로세스가 종료되어 다시 시작됨 (포그라운드 서비스 확인 필요)"); memory.sessionRunning = false }
        else SignalCheck.waiting("세션유지", "출발 후 내비로 갔다가 앱으로 돌아오면 주행 화면이 유지되는지 확인")
        if (mode == RuntimeMode.PLEOS) { SignalCheck.waiting("코칭", "주행 중 코칭이 한 번이라도 나오면 정상"); SignalCheck.startSummary(scope) } else SignalCheck.fail("차량서비스", "Pleos 차량 서비스를 찾지 못해 시연 모드로 동작")
        vehicleSource.start()
        navSource.start()
        locationSource.start()
        uxRestrictions.start()
        scope.launch(Dispatchers.IO) {
            try { recoveryNotice.value = checkpoints.recover(profileStore) }
            catch (_: Exception) { recoveryNotice.value = "중단 기록을 읽지 못했어요. 원본 기록은 보존했습니다" }
            finally { recoveryReady.value = true }
        }
    }

    fun onPermissionsChanged() {
        uxRestrictions.start()
        vehicleSource.refreshPermissions()
        locationSource.start()
        voiceCommands.refreshPermissions()
        speaker.refreshPermissions()
    }

    companion object {
        private const val TAG = "StepDrive"
        const val PLEOS_VEHICLE_SERVICE = "ai.pleos.playground.service.vehicle"

        fun detectMode(context: Context): RuntimeMode {
            if (BuildConfig.FORCE_DEMO) return RuntimeMode.DEMO
            return try {
                context.packageManager.getPackageInfo(PLEOS_VEHICLE_SERVICE, 0)
                RuntimeMode.PLEOS
            } catch (_: PackageManager.NameNotFoundException) {
                RuntimeMode.DEMO
            }
        }
    }
}
