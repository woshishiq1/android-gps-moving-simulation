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

    companion object {
        const val MIN_SPEED_KMH = 0.0      // Minimum speed (stationary)
        const val MAX_SPEED_KMH = 350.0    // Maximum speed (350 km/h - high-speed vehicles)
        const val SPEED_STEP_KMH = 2.0     // Speed step increment (2 km/h)

        // Curve detection and speed adjustment
        private const val CURVE_ANGLE_THRESHOLD = 30.0  // degrees - angle to detect curve
        private const val SEVERE_CURVE_ANGLE = 60.0     // degrees - severe turn
        private const val SHARP_CURVE_ANGLE = 90.0      // degrees - sharp turn
    }

    private var job: Job? = null
    private var paused: Boolean = false

    // GPS update parameters - optimized for balance between smoothness and performance
    private val minUpdateInterval = 250L  // Min GPS update interval (250ms, was 80ms)
    private val maxUpdateInterval = 350L  // Max GPS update interval (350ms, was 120ms)

    // Speed management - save user speed and apply curve reduction
    private var savedSpeedKmh = 45.0      // Original user-set speed (preserved when curves reduce speed)

    // Curve detection state
    private var currentCurveReduction = 1.0  // 1.0 = normal speed, 0.5 = 50% speed reduction
    private var lastBearing = 0.0            // Previous bearing for curve detection

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
    }

    /**
     * Calculate bearing (direction) between two points
     * Used to detect curves and turns
     */
    private fun calculateBearing(from: LatLng, to: LatLng): Double {
        val dLng = to.longitude - from.longitude
        val y = Math.sin(Math.toRadians(dLng)) * Math.cos(Math.toRadians(to.latitude))
        val x = Math.cos(Math.toRadians(from.latitude)) * Math.sin(Math.toRadians(to.latitude)) -
                Math.sin(Math.toRadians(from.latitude)) * Math.cos(Math.toRadians(to.latitude)) * Math.cos(Math.toRadians(dLng))
        val bearing = Math.toDegrees(Math.atan2(y, x))
        return (bearing + 360) % 360  // Normalize to 0-360
    }

    /**
     * Detect curve angle and calculate realistic speed reduction
     * Implements smooth braking BEFORE curve, slow through curve, smooth acceleration AFTER
     *
     * Simpler approach: Just reduce speed based on angle magnitude
     */
    private fun detectCurveAndReduceSpeed(from: LatLng, to: LatLng, next: LatLng?): Double {
        if (next == null) {
            // No next point - gradual recovery
            currentCurveReduction = minOf(1.0, currentCurveReduction + 0.15)
            return currentCurveReduction
        }

        val bearing1 = calculateBearing(from, to)
        val bearing2 = calculateBearing(to, next)

        // Calculate angle difference (curve angle)
        var angleDiff = bearing2 - bearing1
        // Normalize to -180 to 180 range
        if (angleDiff > 180) angleDiff -= 360
        if (angleDiff < -180) angleDiff += 360
        angleDiff = Math.abs(angleDiff)

        // Calculate target reduction based on angle
        // More angle = more reduction (larger curve = slower)
        val targetReduction = when {
            angleDiff < 20.0 -> 1.0      // No curve
            angleDiff < 40.0 -> 0.85     // Slight curve
            angleDiff < 60.0 -> 0.75     // Moderate curve
            angleDiff < 90.0 -> 0.65     // Significant curve
            else -> 0.50                  // Sharp turn
        }

        // Smooth transition to target (85% old + 15% new)
        currentCurveReduction = currentCurveReduction * 0.85 + targetReduction * 0.15

        return currentCurveReduction
    }

    fun start(onPosition: (LatLng) -> Unit = {}, onComplete: (() -> Unit)? = null) {
        stop()
        if (points.size < 2) return

        // Save the user's set speed (will be preserved when curves reduce speed)
        savedSpeedKmh = speedKmh
        SpeedSyncManager.setSavedSpeed(speedKmh.toFloat())

        // Reset curve reduction at start
        currentCurveReduction = 1.0        // Signal to reset - set actual speed to 0 initially
        SpeedSyncManager.setControlSpeed(speedKmh.toFloat())
        SpeedSyncManager.updateActualSpeed(0f)
        SpeedSyncManager.updateCurveReduction(1f)

        job = scope.launch {
            var idx = 0

            while (idx < points.size - 1 && isActive) {
                val a = points[idx]
                val b = points[idx + 1]
                val c = if (idx + 2 < points.size) points[idx + 2] else null  // Next segment for curve detection

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

                    // Detect curve AND UPDATE reduction EVERY iteration (important!)
                    val curveSpeedReduction = detectCurveAndReduceSpeed(a, b, c)

                    // Generate realistic update interval
                    val currentUpdateInterval = getRealisticUpdateInterval()

                    // Calculate current speed with curve reduction applied
                    // This makes speed automatically reduce on curves to look realistic
                    val adjustedSpeedKmh = speedKmh * curveSpeedReduction

                    // Sync actual simulation speed with Xposed hooks via SpeedSyncManager
                    SpeedSyncManager.setControlSpeed(speedKmh.toFloat())  // Control speed (constant)
                    SpeedSyncManager.updateActualSpeed(adjustedSpeedKmh.toFloat())  // Actual speed (varies with curves)
                    SpeedSyncManager.updateCurveReduction(curveSpeedReduction.toFloat())  // Curve factor for sensors

                    val currentSpeedMs = adjustedSpeedKmh * 1000.0 / 3600.0
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

    // Speed control methods
    fun increaseSpeed() {
        val newSpeed = (speedKmh + SPEED_STEP_KMH).coerceAtMost(MAX_SPEED_KMH)
        speedKmh = newSpeed
        savedSpeedKmh = newSpeed
        SpeedSyncManager.setSavedSpeed(newSpeed.toFloat())
        // Sync immediately when user changes speed (apply curve reduction)
        if (isRunning()) {
            SpeedSyncManager.updateActualSpeed((newSpeed * currentCurveReduction).toFloat())
        }
    }

    fun decreaseSpeed() {
        val newSpeed = (speedKmh - SPEED_STEP_KMH).coerceAtLeast(MIN_SPEED_KMH)
        speedKmh = newSpeed
        savedSpeedKmh = newSpeed
        SpeedSyncManager.setSavedSpeed(newSpeed.toFloat())
        // Sync immediately when user changes speed (apply curve reduction)
        if (isRunning()) {
            SpeedSyncManager.updateActualSpeed((newSpeed * currentCurveReduction).toFloat())
        }
    }

    fun setSpeed(newSpeedKmh: Double) {
        speedKmh = newSpeedKmh.coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH)
        savedSpeedKmh = speedKmh
        SpeedSyncManager.setSavedSpeed(speedKmh.toFloat())
        // Sync immediately when user changes speed (apply curve reduction)
        if (isRunning()) {
            SpeedSyncManager.updateActualSpeed((speedKmh * currentCurveReduction).toFloat())
        }
    }

    fun getSpeed(): Double {
        return speedKmh
    }

    fun getSavedSpeed(): Double {
        return savedSpeedKmh
    }

    /**
     * Get actual simulation speed with curve reduction applied
     * This is what the fake GPS actually shows
     */
    fun getActualSpeed(): Double {
        return speedKmh * currentCurveReduction
    }

    /**
     * Get curve reduction factor (for UI to show when curving)
     */
    fun getCurveReduction(): Double {
        return currentCurveReduction
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
        SpeedSyncManager.reset()  // Reset synced speed when simulation stops
    }

    fun isRunning(): Boolean {
        return job?.isActive == true && !paused
    }

    fun setSpeedKmh(v: Double) {
        speedKmh = v
        savedSpeedKmh = v
        SpeedSyncManager.setSavedSpeed(v.toFloat())
        // Sync immediately when user changes speed (apply curve reduction)
        if (isRunning()) {
            SpeedSyncManager.updateActualSpeed((v * currentCurveReduction).toFloat())
        }
    }
}
