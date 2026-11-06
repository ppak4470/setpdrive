package kr.stepdrive.app.location

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.stepdrive.core.model.LatLng

data class Fix(val position: LatLng, val headingDeg: Double?, val tMs: Long = System.currentTimeMillis())

/** Supplementary position source; the navigation's own position is preferred when routing is active. */
interface LocationSource {
    val fix: StateFlow<Fix?>
    fun start()
    fun stop()
}

class NoLocationSource : LocationSource {
    override val fix: StateFlow<Fix?> = MutableStateFlow(null)
    override fun start() = Unit
    override fun stop() = Unit
}

/** Pleos FusedLocation SDK adapter. */
class PleosLocationSource(private val context: Context) : LocationSource {
    private val _fix = MutableStateFlow<Fix?>(null)
    override val fix: StateFlow<Fix?> = _fix.asStateFlow()
    private val fused = FusedLocation(context.applicationContext)
    private var started = false

    private val listener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            if (location == null) { _fix.value = null; return }
            _fix.value = Fix(LatLng(location.latitude, location.longitude), location.heading.toDouble())
        }
        override fun onError(exception: Exception) { _fix.value = null; Log.w(TAG, "fused location: $exception") }
    }

    override fun start() {
        if (context.checkSelfPermission("pleos.car.permission.FUSED_LOCATION") != android.content.pm.PackageManager.PERMISSION_GRANTED) { stop(); return }
        if (started) return
        runCatching {
            if (!fused.isSupported()) { Log.w(TAG, "FusedLocation service not installed"); return }
            fused.initialize()
            fused.registerFusedLocationCallback(listener)
            started = true
        }.onFailure { Log.w(TAG, "FusedLocation unavailable: $it") }
    }

    override fun stop() {
        _fix.value = null
        if (!started) return
        started = false
        runCatching { fused.unregisterFusedLocationCallback(listener); fused.release() }
    }

    private companion object { const val TAG = "PleosLocation" }
}
