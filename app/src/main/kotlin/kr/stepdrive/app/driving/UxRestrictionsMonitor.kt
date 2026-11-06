package kr.stepdrive.app.driving

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kr.stepdrive.app.vehicle.VehicleSignalSource
import kr.stepdrive.core.model.Gear

/** True while the car asks for distraction-optimized UI: screens show voice-first, minimal content. */
interface UxRestrictionsMonitor {
    val restricted: StateFlow<Boolean>
    fun start()
    fun stop()
}

/** Android Automotive CarUxRestrictionsManager. */
class CarUxRestrictionsMonitor(private val context: Context) : UxRestrictionsMonitor {
    private val _restricted = MutableStateFlow(true)
    override val restricted: StateFlow<Boolean> = _restricted
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    @Volatile private var started = false
    private var generation = 0L

    @Synchronized override fun start() {
        if (started) return
        started = true
        val run = ++generation
        _restricted.value = true
        runCatching {
            car = Car.createCar(context.applicationContext, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                synchronized(this) {
                    if (!started || generation != run) return@createCar
                    // Fail closed until the new service supplies its current restrictions.
                    _restricted.value = true
                    runCatching { manager?.unregisterListener() }
                    manager = null
                    if (ready) runCatching {
                        val m = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                        manager = m
                        m.registerListener { restrictions ->
                            synchronized(this) {
                                if (started && generation == run && manager === m)
                                    _restricted.value = restrictions.isRequiresDistractionOptimization
                            }
                        }
                        _restricted.value = m.currentCarUxRestrictions?.isRequiresDistractionOptimization != false
                    }.onFailure { Log.w("UxRestrictions", "restriction refresh failed", it) }
                }
            }
        }.onFailure {
            _restricted.value = true
            started = false
            manager = null
            runCatching { car?.disconnect() }
            car = null
            Log.w("UxRestrictions", "unavailable: $it")
        }
    }

    @Synchronized override fun stop() {
        started = false
        generation++
        _restricted.value = true
        runCatching { manager?.unregisterListener(); car?.disconnect() }
        manager = null
        car = null
        _restricted.value = true
    }
}

/** Demo rule of thumb: anything but Park counts as driving. */
class DemoUxRestrictionsMonitor(vehicle: VehicleSignalSource) : UxRestrictionsMonitor {
    override val restricted: StateFlow<Boolean> = vehicle.samples
        .map { it.gear != Gear.PARK }
        .stateIn(CoroutineScope(Dispatchers.Default), SharingStarted.Eagerly, true)
    override fun start() = Unit
    override fun stop() = Unit
}
