package io.github.jqssun.gpssetter.utils

import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.*

/**
 * Simulates movement along a polyline (list of LatLng) at a given speed (km/h).
 * Provides position updates via callback - GPS mocking is handled by the caller
 * using the same method as the "Set Location" feature for consistency and stealth.
 */
class RouteSimulator(
    private val points: List<LatLng>,
    private var speedKmh: Double = 50.0,
    private val updateIntervalMs: Long = 150L,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {

    private var job: Job? = null
    private var paused: Boolean = false

    fun start(onPosition: (LatLng) -> Unit = {}, onComplete: (() -> Unit)? = null) {
        stop()
        if (points.size < 2) return
        job = scope.launch {
            var idx = 0
            while (idx < points.size - 1 && isActive) {
                val a = points[idx]
                val b = points[idx + 1]
                val segMeters = PolylineUtils.haversineDistanceMeters(a, b)
                if (segMeters <= 0.0) {
                    idx++
                    continue
                }
                // speed m/s
                val speedMs = speedKmh * 1000.0 / 3600.0
                var traveled = 0.0
                val stepMeters = speedMs * (updateIntervalMs.toDouble() / 1000.0)
                while (traveled < segMeters && isActive) {
                    if (paused) {
                        delay(updateIntervalMs)
                        continue
                    }
                    val frac = (traveled / segMeters).coerceIn(0.0, 1.0)
                    val lat = a.latitude + (b.latitude - a.latitude) * frac
                    val lng = a.longitude + (b.longitude - a.longitude) * frac
                    val pos = LatLng(lat, lng)
                    // Don't update GPS here - let MapActivity handle it via callback
                    // This ensures we use the same GPS mocking method as "Set Location" feature
                    onPosition(pos)
                    traveled += stepMeters
                    delay(updateIntervalMs)
                }
                // ensure arrive at b - let MapActivity handle GPS update
                onPosition(b)
                idx++
            }
            // finished route
            onComplete?.invoke()
        }
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun isRunning(): Boolean {
        return job?.isActive == true && !paused
    }

    fun setSpeedKmh(v: Double) {
        speedKmh = v
    }
}
