package kr.stepdrive.app.vehicle

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kr.stepdrive.app.demo.DemoClock
import kr.stepdrive.core.model.VehicleSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

/** The only thing the rest of the app knows about the car: a stream of [VehicleSample]s. */
interface VehicleSignalSource {
    val samples: StateFlow<VehicleSample>
    fun refreshPermissions() = Unit
    fun start()
    fun stop()
}

/** Replays the scripted drive; shares the clock with [kr.stepdrive.app.navi.DemoNavSource]. */
class DemoVehicleSource(private val clock: DemoClock) : VehicleSignalSource {
    override val samples: StateFlow<VehicleSample> = clock.frame
        .map { it.sample }
        .stateIn(CoroutineScope(kotlinx.coroutines.Dispatchers.Default), SharingStarted.Eagerly, clock.frame.value.sample)

    override fun start() = Unit
    override fun stop() = clock.pause()
}
