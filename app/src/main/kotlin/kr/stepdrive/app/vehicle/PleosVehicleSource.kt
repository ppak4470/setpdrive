package kr.stepdrive.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kr.stepdrive.app.diag.SignalCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kr.stepdrive.core.model.Gear
import kr.stepdrive.core.model.TurnSignal
import kr.stepdrive.core.model.VehicleSample
import kr.stepdrive.core.model.freshAt

/**
 * Real signals on Pleos Connect.
 *  - Pleos Vehicle SDK: turn signal (CAR_LIGHTS), brake pedal, steering wheel angle, odometer (CAR_DRIVE)
 *  - Android Automotive CarPropertyManager: vehicle speed (PERF_VEHICLE_SPEED) and gear (GEAR_SELECTION),
 *    which is what the Pleos Gear API itself now recommends.
 * Every SDK call is wrapped: a missing signal leaves the field null and the rules skip it.
 */
class PleosVehicleSource(private val context: Context, private val scope: CoroutineScope) : VehicleSignalSource {
    private val _samples = MutableStateFlow(VehicleSample(System.currentTimeMillis()))
    override val samples: StateFlow<VehicleSample> = _samples.asStateFlow()

    private val vehicle = Vehicle(context.applicationContext)
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var odometerJob: Job? = null
    @Volatile private var started = false
    private var pleosInitialized = false
    @Volatile private var turnRegistered = false
    @Volatile private var brakeRegistered = false
    @Volatile private var steeringRegistered = false
    private var speedRegistered = false
    private var gearRegistered = false

    private val turnSignalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) { logIfChanged("turnSignal", state.toString()); if (state != null && state != TurnSignalValue.OFF) SignalCheck.ok("방향지시등", "$state 수신"); update { it.copy(turnSignal = state.toModel()) } }
        override fun onFailed(e: Exception) { turnRegistered = false; Log.w(TAG, "turn signal: $e"); SignalCheck.fail("방향지시등", e.toString()); update { it.copy(turnSignal = TurnSignal.UNKNOWN) } }
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) { logIfChanged("brake", status.toString()); if (status == true) SignalCheck.ok("브레이크", "밟음 수신"); update { it.copy(brakePressed = status) } }
        override fun onFailed(e: Exception) { brakeRegistered = false; Log.w(TAG, "brake: $e"); SignalCheck.fail("브레이크", e.toString()); update { it.copy(brakePressed = null) } }
    }
    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) { logIfChanged("steering", angle?.toInt()?.toString()?.plus("deg")); if (angle != null && kotlin.math.abs(angle) >= 5f) SignalCheck.ok("핸들", "${angle.toInt()}도 수신"); update { it.copy(steeringAngleDeg = angle) } }
        override fun onFailed(e: Exception) { steeringRegistered = false; Log.w(TAG, "steering: $e"); SignalCheck.fail("핸들", e.toString()); update { it.copy(steeringAngleDeg = null) } }
    }
    private val carCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (!started) return
            // A callback already queued before permission revocation must not restore speed.
            if (value.propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED &&
                context.checkSelfPermission("android.car.permission.CAR_SPEED") != PackageManager.PERMISSION_GRANTED) {
                update { it.copy(speedKmh = null) }
                return
            }
            if (value.status != CarPropertyValue.STATUS_AVAILABLE) { onErrorEvent(value.propertyId, value.areaId); return }
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> (value.value as? Float)?.let { mps -> logSpeed(mps * 3.6f); if (mps * 3.6f >= 3f) SignalCheck.ok("속도", "${(mps * 3.6f).toInt()}km/h 수신"); update { it.copy(speedKmh = mps * 3.6f, speedTMs = System.currentTimeMillis()) } }
                VehiclePropertyIds.GEAR_SELECTION -> (value.value as? Int)?.let { g -> logIfChanged("gear", g.toGear().toString()); if (g.toGear() != Gear.PARK && g.toGear() != Gear.UNKNOWN) SignalCheck.ok("기어", "${g.toGear()} 수신"); update { it.copy(gear = g.toGear()) } }
            }
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) { Log.w(TAG, "car property error $propertyId"); SignalCheck.fail(if (propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) "속도" else "기어", "car property error $propertyId")
            update { if (propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) it.copy(speedKmh = null) else it.copy(gear = Gear.UNKNOWN) }
        }
    }

    override fun start() {
        if (started) return
        started = true
        connectPleos()
        connectCar()

        odometerJob = scope.launch {
            var nextPoll = 0L
            while (isActive) {
                val now = System.currentTimeMillis()
                _samples.update { current ->
                    current.freshAt(now).let { if (it != current) it.copy(tMs = now) else current }
                }
                if (now >= nextPoll) {
                    nextPoll = now + 5_000
                    connectPleos()
                    connectCar()
                    registerCarProperties()
                    runCatching {
                        vehicle.getOdometer().getCurrentDistanceDriven(
                            onComplete = { km -> update { it.copy(odometerKm = km) } },
                            onFailed = { update { it.copy(odometerKm = null) }; SignalCheck.fail("주행거리", it.toString()) },
                        )
                    }.onFailure { update { it.copy(odometerKm = null) } }
                }
                delay(500)
            }
        }
    }

    @Synchronized private fun connectPleos() {
        if (!started) return
        runCatching {
            if (!pleosInitialized) { vehicle.initialize(); pleosInitialized = true }
            if (!turnRegistered) {
                runCatching { vehicle.getTurnSignal().unregisterTurnSignalState(turnSignalListener) }
                turnRegistered = true
                runCatching { vehicle.getTurnSignal().registerTurnSignalState(turnSignalListener) }
                    .onFailure { turnRegistered = false; update { it.copy(turnSignal = TurnSignal.UNKNOWN) } }
            }
            if (!brakeRegistered) {
                runCatching { vehicle.getBrake().unregisterBrakePedalStatus(brakeListener) }
                brakeRegistered = true
                runCatching { vehicle.getBrake().registerBrakePedalStatus(brakeListener) }
                    .onFailure { brakeRegistered = false; update { it.copy(brakePressed = null) } }
            }
            if (!steeringRegistered) {
                runCatching { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener) }
                steeringRegistered = true
                runCatching { vehicle.getSteeringWheel().registerSteeringWheelAngle(steeringListener) }
                    .onFailure { steeringRegistered = false; update { it.copy(steeringAngleDeg = null) } }
            }
        }.onFailure { Log.w(TAG, "Pleos Vehicle SDK unavailable: $it"); SignalCheck.fail("차량서비스", it.toString()); SignalCheck.fail("방향지시등", "차량서비스 실패"); SignalCheck.fail("핸들", "차량서비스 실패"); SignalCheck.fail("브레이크", "차량서비스 실패") }
    }

    override fun refreshPermissions() {
        if (!started) start() else { connectPleos(); connectCar(); registerCarProperties() }
    }

    @Synchronized private fun connectCar() {
        if (!started || car != null) return
        runCatching {
            car = Car.createCar(context.applicationContext, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (!started) return@createCar
                synchronized(this) {
                    speedRegistered = false
                    gearRegistered = false
                    propertyManager = if (ready) connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager else null
                }
                if (ready) { registerCarProperties(); SignalCheck.ok("차량서비스", "Car 서비스 연결/복구") }
                else { update { it.copy(speedKmh = null, gear = Gear.UNKNOWN) }; SignalCheck.fail("차량서비스", "Car 서비스 연결 끊김") }
            }
        }.onFailure { Log.w(TAG, "Car connection failed", it) }
    }

    @Synchronized private fun registerCarProperties() {
        if (!started) return
        runCatching {
            val pm = propertyManager ?: return
            val speedGranted = context.checkSelfPermission("android.car.permission.CAR_SPEED") == PackageManager.PERMISSION_GRANTED
            if (!speedGranted) {
                if (speedRegistered) pm.unregisterCallback(carCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED)
                speedRegistered = false
                update { it.copy(speedKmh = null) }
                SignalCheck.waiting("속도", "속도 권한을 허용하면 연결을 다시 시도합니다")
            } else if (!speedRegistered) {
                speedRegistered = pm.registerCallback(carCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_UI)
                if (speedRegistered) SignalCheck.waiting("속도", "속도 신호 대기")
                else SignalCheck.fail("속도", "속도 신호 구독 실패")
            }
            if (!gearRegistered) {
                gearRegistered = pm.registerCallback(carCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
                if (gearRegistered) SignalCheck.waiting("기어", "기어 신호 대기")
                else SignalCheck.fail("기어", "기어 신호 구독 실패")
            }
        }.onFailure { Log.w(TAG, "android.car unavailable", it); SignalCheck.fail("차량서비스", it.toString()) }
    }

    override fun stop() {
        if (!started) return
        started = false
        speedRegistered = false
        gearRegistered = false
        turnRegistered = false; brakeRegistered = false; steeringRegistered = false; pleosInitialized = false
        _samples.value = VehicleSample(System.currentTimeMillis())
        odometerJob?.cancel()
        runCatching {
            vehicle.getTurnSignal().unregisterTurnSignalState(turnSignalListener)
            vehicle.getBrake().unregisterBrakePedalStatus(brakeListener)
            vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener)
            vehicle.release()
        }
        runCatching { propertyManager?.unregisterCallback(carCallback); car?.disconnect() }
        propertyManager = null
        car = null
    }

    private val lastLogged = HashMap<String, String>()
    /** Logs a signal only when its value changes, so the log stays readable. */
    private fun logIfChanged(name: String, value: String?) {
        val v = value ?: "null"
        if (lastLogged[name] != v) { lastLogged[name] = v; Log.i(TAG, "$name=$v") }
    }
    private var lastLoggedSpeed = -1
    private fun logSpeed(kmh: Float) {
        val r = kmh.toInt()
        if (r != lastLoggedSpeed) { lastLoggedSpeed = r; Log.i(TAG, "speed=${r}km/h") }
    }

    private inline fun update(block: (VehicleSample) -> VehicleSample) {
        if (!started) return
        _samples.update { block(it).copy(tMs = System.currentTimeMillis()) }
    }

    private fun TurnSignalValue?.toModel() = when (this) {
        TurnSignalValue.LEFT -> TurnSignal.LEFT
        TurnSignalValue.RIGHT -> TurnSignal.RIGHT
        TurnSignalValue.OFF -> TurnSignal.OFF
        else -> TurnSignal.UNKNOWN
    }

    private fun Int.toGear() = when (this) {
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        VehicleGear.GEAR_DRIVE, VehicleGear.GEAR_FIRST, VehicleGear.GEAR_SECOND, VehicleGear.GEAR_THIRD -> Gear.DRIVE
        else -> Gear.UNKNOWN
    }

    private companion object { const val TAG = "PleosVehicle" }
}
