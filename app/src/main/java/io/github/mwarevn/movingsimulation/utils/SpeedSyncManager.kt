package io.github.mwarevn.movingsimulation.utils

import java.util.concurrent.atomic.AtomicReference

/**
 * SpeedSyncManager - Synchronizes control speed and actual simulated speed with Xposed hooks
 *
 * Problem:
 * - User sets speed on slider (e.g., 40 km/h) - this is CONTROL SPEED
 * - RouteSimulator applies curve reduction (e.g., 40 × 0.4 = 16 km/h) - this is ACTUAL SPEED
 * - LocationHook needs ACTUAL speed for GPS
 * - SensorSpoofHook needs CURVE REDUCTION for sensor adjustment
 * - UI needs to show both CONTROL (what user set) and ACTUAL (after curves)
 *
 * Solution: Track both speeds separately using atomic references
 */
object SpeedSyncManager {

    // Saved speed - what user originally set on slider (preserved during curve reduction)
    // Example: User sets 108 km/h, this stays 108 even when curves reduce to 86 km/h
    private val savedSpeedKmh = AtomicReference<Float>(52f)

    // Control speed - what user set on slider (0-400 km/h, constant while driving)
    private val controlSpeedKmh = AtomicReference<Float>(52f)

    // Actual simulated speed - after curve reduction applied (varies with curves)
    // Example: 40 km/h control × 0.4 curve reduction = 16 km/h actual
    private val actualSpeedKmh = AtomicReference<Float>(0f)

    // Current curve reduction factor (0-1) for sensor spoofing sync
    // 1.0 = normal, 0.5 = half speed, 0.3 = sharp turn
    private val currentCurveReduction = AtomicReference<Float>(1f)

    // Current bearing (0-360 degrees) synchronized from RouteSimulator
    // Updated during route navigation to match actual movement direction
    // Used by LocationHook and SensorSpoofHook to show correct compass direction
    private val currentBearing = AtomicReference<Float>(0f)

    /**
     * Update bearing synchronized from route movement
     * Called by RouteSimulator when navigating route
     * @param bearing Current bearing in degrees (0-360)
     */
    fun updateBearing(bearing: Float) {
        val normalizedBearing = (bearing % 360 + 360) % 360  // Normalize to 0-360
        currentBearing.set(normalizedBearing)
        // CRITICAL: Also save to SharedPreferences for Xposed hooks (cross-process)
        PrefManager.syncedBearing = normalizedBearing
    }

    /**
     * Get current bearing synchronized from route movement
     * Used by LocationHook for GPS bearing and SensorSpoofHook for magnetometer
     * @return Bearing in degrees (0-360)
     */
    fun getBearing(): Float {
        return currentBearing.get() ?: 0f
    }

    /**
     * Update control speed (from UI slider)
     * This is the speed the user set, not affected by curves
     */
    fun setControlSpeed(speed: Float) {
        controlSpeedKmh.set(speed.coerceIn(0f, 400f))
    }

    /**
     * Update actual simulation speed from RouteSimulator
     * Called by RouteSimulator during movement simulation (after curve reduction)
     * @param speed The actual speed being used (control speed × curve reduction)
     */
    fun updateActualSpeed(speed: Float) {
        val clampedSpeed = speed.coerceIn(0f, 400f)
        actualSpeedKmh.set(clampedSpeed)
        // CRITICAL: Also save to SharedPreferences for Xposed hooks (cross-process)
        PrefManager.syncedActualSpeed = clampedSpeed
    }

    /**
     * Update curve reduction factor for sensor sync
     * Called by RouteSimulator when detecting curves
     * @param reduction Reduction factor: 1.0 = normal, 0.5 = half speed, 0.3 = sharp turn
     */
    fun updateCurveReduction(reduction: Float) {
        val clampedReduction = reduction.coerceIn(0f, 1f)
        currentCurveReduction.set(clampedReduction)
        // CRITICAL: Also save to SharedPreferences for Xposed hooks (cross-process)
        PrefManager.syncedCurveReduction = clampedReduction
    }

    /**
     * Get control speed (what's set on slider)
     * Used by UI to display what user selected
     */
    fun getControlSpeed(): Float {
        return controlSpeedKmh.get() ?: 52f
    }

    /**
     * Get actual simulation speed (after curve reduction)
     * Used by LocationHook for GPS speed
     */
    fun getActualSpeed(): Float {
        return actualSpeedKmh.get() ?: 0f
    }

    /**
     * Get current curve reduction (used by SensorSpoofHook)
     * @return Reduction factor 0-1
     */
    fun getCurveReduction(): Float {
        return currentCurveReduction.get() ?: 1f
    }

    /**
     * Get saved speed (user's original set speed, not affected by curve reduction)
     * Used by MapActivity to restore slider position after curves
     */
    fun getSavedSpeed(): Float {
        return savedSpeedKmh.get() ?: 52f
    }

    /**
     * Update saved speed when user changes slider
     * This is the "true" speed the user wants, preserved during curve reduction
     */
    fun setSavedSpeed(speed: Float) {
        savedSpeedKmh.set(speed.coerceIn(0f, 400f))
        // Also update control speed for GPS
        setControlSpeed(speed)
    }

    /**
     * Reset to default values (when simulation stops)
     * Default speed is 52 km/h (default motorbike speed)
     */
    fun reset() {
        savedSpeedKmh.set(52f)
        controlSpeedKmh.set(52f)
        actualSpeedKmh.set(0f)
        currentCurveReduction.set(1f)
        currentBearing.set(0f)  // Reset bearing to North
        // CRITICAL: Also reset SharedPreferences for Xposed hooks
        PrefManager.syncedActualSpeed = 0f
        PrefManager.syncedBearing = 0f
        PrefManager.syncedCurveReduction = 1f
    }

    /**
     * Convert speed from km/h to m/s
     * Used by LocationHook when setting Location.speed
     */
    fun speedKmhToMs(speedKmh: Float): Float {
        return speedKmh * 1000f / 3600f  // km/h * 1000 / 3600 = m/s
    }

    /**
     * Convert speed from m/s to km/h
     */
    fun speedMsToKmh(speedMs: Float): Float {
        return speedMs * 3600f / 1000f
    }
}
