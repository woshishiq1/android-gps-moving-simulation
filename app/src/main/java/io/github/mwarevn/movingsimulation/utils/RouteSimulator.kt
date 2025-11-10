package io.github.mwarevn.movingsimulation.utils

import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.*

/**
 * RouteSimulator - Enhanced GPS movement simulation for route navigation
 * Simulates movement along a route with configurable speed and realistic GPS behavior
 *
 * Anti-detection features:
 * - Variable GPS update intervals (180-320ms)
 * - Realistic GPS jitter and timing variance
 * - Natural GPS behavior patterns
 */
class RouteSimulator(
    private val points: List<LatLng>,
    private var speedKmh: Double = 45.0, // Default motorbike speed
    private val updateIntervalMs: Long = 250L, // Base interval - will be randomized
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {

    private var job: Job? = null
    private var paused: Boolean = false

    // Anti-detection parameters
    private val minUpdateInterval = 228L // Safe min GPS update interval (increased from 180ms)
    private val maxUpdateInterval = 350L // Max GPS update interval (increased proportionally)

    /**
     * Generate realistic GPS update interval with natural variance
     * Real GPS devices don't update at perfectly consistent intervals
     * Safe range: 228-350ms to prevent GPS loss
     */
    private fun getRealisticUpdateInterval(): Long {
        // Most updates around base interval, with some outliers
        val random = Math.random()
        return when {
            random < 0.7 -> { // 70% normal updates (248-288ms)
                (minUpdateInterval + 20 + Math.random() * 40).toLong()
            }
            random < 0.9 -> { // 20% faster updates (228-248ms)
                (minUpdateInterval + Math.random() * 20).toLong()
            }
            else -> { // 10% slower updates (288-350ms)
                (maxUpdateInterval - 62 + Math.random() * 62).toLong()
            }
        }
    }

    /**
     * Add realistic GPS jitter with variable intensity
     * Real GPS has different accuracy depending on conditions
     */
    private fun addRealisticGpsJitter(position: LatLng): LatLng {
        // Variable GPS accuracy simulation
        val baseJitter = 0.000003 // ~0.3m base
        val jitterMultiplier = 0.5 + Math.random() * 1.0 // 0.5x to 1.5x variance

        val jitterLat = (Math.random() - 0.5) * baseJitter * jitterMultiplier
        val jitterLng = (Math.random() - 0.5) * baseJitter * jitterMultiplier

        return LatLng(
            position.latitude + jitterLat,
            position.longitude + jitterLng
        )
    }    fun start(onPosition: (LatLng) -> Unit = {}, onComplete: (() -> Unit)? = null) {
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

                    // Generate realistic update interval for anti-detection
                    val currentUpdateInterval = getRealisticUpdateInterval()

                    // Calculate current speed and step distance (allows real-time speed changes)
                    val currentSpeedMs = speedKmh * 1000.0 / 3600.0
                    val stepMeters = currentSpeedMs * (currentUpdateInterval.toDouble() / 1000.0)

                    val frac = (traveled / segMeters).coerceIn(0.0, 1.0)
                    val lat = a.latitude + (b.latitude - a.latitude) * frac
                    val lng = a.longitude + (b.longitude - a.longitude) * frac
                    val position = LatLng(lat, lng)

                    // Add realistic GPS jitter with variable intensity
                    val gpsPosition = addRealisticGpsJitter(position)
                    onPosition(gpsPosition)

                    traveled += stepMeters
                    // Use variable interval for realistic GPS timing
                    delay(currentUpdateInterval)
                }

                // Ensure we arrive at exact destination point
                onPosition(addRealisticGpsJitter(b))
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
