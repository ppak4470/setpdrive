package kr.stepdrive.app.navi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kr.stepdrive.app.demo.DemoClock
import kr.stepdrive.core.model.LatLng
import kr.stepdrive.core.model.NavState

data class Destination(val name: String, val position: LatLng, val address: String = "")

/** Navigation as the app sees it: a state stream plus "drive this course". */
interface NavSource {
    val nav: StateFlow<NavState>
    fun start()
    fun stop()
    /** Sets the destination and the hazard-avoiding waypoints on the car's navigation. */
    fun requestRoute(destination: Destination, waypoints: List<Destination>): Boolean
    /** Recent destinations and bookmarks from the car's navigation; course candidates are built from these. */
    val recentDestinations: StateFlow<List<Destination>> get() = MutableStateFlow(emptyList())
    fun refreshDestinations() = Unit
    /** True means a request was sent, not that a replacement route is ready. */
    fun requestReroute(): Boolean = false
    /** The destination currently set on the car's navigation (what the driver picked there), if any. */
    val currentDestination: StateFlow<Destination?> get() = MutableStateFlow(null)
    /** Opens the car navigation's own place search with [keyword]; the driver picks a result there. */
    fun openSearch(keyword: String): Boolean = false
}

class DemoNavSource(private val clock: DemoClock) : NavSource {
    override val nav: StateFlow<NavState> = clock.frame
        .map { it.nav }
        .stateIn(CoroutineScope(Dispatchers.Default), SharingStarted.Eagerly, clock.frame.value.nav)

    override fun start() = Unit
    override fun stop() = clock.pause()
    override fun requestRoute(destination: Destination, waypoints: List<Destination>): Boolean {
        clock.restart(); clock.play(); return true
    }
}
