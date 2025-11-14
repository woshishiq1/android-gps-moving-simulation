package io.github.mwarevn.movingsimulation.utils

import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.*

/**
 * RouteSimulator - Optimized GPS movement simulation for route navigation
 * Simulates movement along a route with configurable speed and realistic GPS behavior
 *
 * Performance Optimizations:
 * - Reduced update frequency to 300ms (from 100ms) to lower CPU/GPU load
 * - Balanced between smoothness and battery efficiency
 * - Realistic GPS jitter (1-2 meters) to prevent GPS loss detection
 * - Natural GPS behavior patterns that mimic real mobile devices
 * - Prevents route cutting at turns with accurate interpolation
 */
class RouteSimulator(
    private val points: List<LatLng>,
    private var speedKmh: Double = 45.0, // Default motorbike speed
    private val updateIntervalMs: Long = 300L, // Optimized: 300ms for better performance (was 100ms)
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {

    private var job: Job? = null
    private var paused: Boolean = false

    // GPS update parameters - optimized for balance between smoothness and performance
    private val minUpdateInterval = 250L  // Min GPS update interval (250ms, was 80ms)
    private val maxUpdateInterval = 350L  // Max GPS update interval (350ms, was 120ms)

    /**
     * Generate realistic GPS update interval with minimal variance
     * Optimized for better battery life while maintaining smooth movement
     * Range: 250-350ms for balanced performance (was 80-120ms)
     */
    private fun getRealisticUpdateInterval(): Long {
        // Most updates around 300ms, with slight variance
        val random = Math.random()
        return when {
            random < 0.8 -> { // 80% normal updates (280-320ms)
                (280 + Math.random() * 40).toLong()
            }
            random < 0.95 -> { // 15% faster updates (250-280ms)
                (minUpdateInterval + Math.random() * 30).toLong()
            }
            else -> { // 5% slower updates (320-350ms)
                (320 + Math.random() * 30).toLong()
            }
        }
    }

    /**
     * Add realistic GPS jitter for natural behavior
     * Jitter range: 1-3 meters (realistic for mobile GPS)
     * This prevents GPS loss and makes movement look natural
     */
    private fun addRealisticGpsJitter(position: LatLng): LatLng {
        // Realistic GPS jitter - approximately 1-3 meters (natural mobile GPS accuracy)
        val baseJitter = 0.000015 // ~1.5m base (1 degree latitude ≈ 111km)
        val jitterMultiplier = 0.7 + Math.random() * 0.6 // 0.7x to 1.3x variance (1-2m range)

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
                
                // Skip very short segments to avoid calculation issues
                if (segMeters <= 0.1) {
                    idx++
                    continue
                }

                var traveled = 0.0
                
                while (traveled < segMeters && isActive) {
                    if (paused) {
                        delay(updateIntervalMs)
                        continue
                    }

                    // Generate realistic update interval
                    val currentUpdateInterval = getRealisticUpdateInterval()

                    // Calculate current speed and step distance (allows real-time speed changes)
                    val currentSpeedMs = speedKmh * 1000.0 / 3600.0
                    val stepMeters = currentSpeedMs * (currentUpdateInterval.toDouble() / 1000.0)

                    // Calculate fraction - ensure we don't overshoot the segment
                    val nextTraveled = traveled + stepMeters
                    val actualFrac = if (nextTraveled >= segMeters) {
                        1.0 // Exactly at point B
                    } else {
                        (traveled / segMeters).coerceIn(0.0, 1.0)
                    }

                    // Linear interpolation between points A and B
                    val lat = a.latitude + (b.latitude - a.latitude) * actualFrac
                    val lng = a.longitude + (b.longitude - a.longitude) * actualFrac
                    val position = LatLng(lat, lng)

                    // Add minimal GPS jitter for realism
                    val gpsPosition = addRealisticGpsJitter(position)
                    onPosition(gpsPosition)

                    // Move forward
                    traveled += stepMeters
                    
                    // Use variable interval for smooth GPS timing
                    delay(currentUpdateInterval)
                }

                // Move to next segment
                idx++
            }

            // Ensure we end exactly at the last point
            if (points.isNotEmpty()) {
                onPosition(addRealisticGpsJitter(points.last()))
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
