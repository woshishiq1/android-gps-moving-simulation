package io.github.mwarevn.movingsimulation.utils

import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.*

/**
 * RouteSimulator - Enhanced GPS movement simulation for route navigation
 * Simulates movement along a route with configurable speed and realistic GPS behavior
 *
 * Optimized for GPS stability:
 * - Single frequency 250ms updates for optimal balance
 * - Light GPS jitter for realism
 * - Stable performance without complexity
 */
class RouteSimulator(
    private val points: List<LatLng>,
    private var speedKmh: Double = 45.0, // Default motorbike speed
    private val updateIntervalMs: Long = 250L, // Optimal balance: smooth + stable
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {

    private var job: Job? = null
    private var paused: Boolean = false

    /**
     * Add light GPS jitter for realistic behavior (±0.3m)
     */
    private fun addGpsJitter(position: LatLng): LatLng {
        val jitterLat = (Math.random() - 0.5) * 0.000003 // ~0.3m jitter
        val jitterLng = (Math.random() - 0.5) * 0.000003
        return LatLng(
            position.latitude + jitterLat,
            position.longitude + jitterLng
        )
    }

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

                var traveled = 0.0
                while (traveled < segMeters && isActive) {
                    if (paused) {
                        delay(updateIntervalMs)
                        continue
                    }

                    // Calculate current speed and step distance (allows real-time speed changes)
                    val currentSpeedMs = speedKmh * 1000.0 / 3600.0
                    val stepMeters = currentSpeedMs * (updateIntervalMs.toDouble() / 1000.0)

                    val frac = (traveled / segMeters).coerceIn(0.0, 1.0)
                    val lat = a.latitude + (b.latitude - a.latitude) * frac
                    val lng = a.longitude + (b.longitude - a.longitude) * frac
                    val position = LatLng(lat, lng)

                    // Add realistic GPS jitter
                    val gpsPosition = addGpsJitter(position)
                    onPosition(gpsPosition)

                    traveled += stepMeters
                    delay(updateIntervalMs)
                }

                // Ensure we arrive at exact destination point
                onPosition(addGpsJitter(b))
                idx++
            }

            // Finished route
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
