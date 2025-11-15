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
    private val savedSpeedKmh = AtomicReference<Float>(40f)
    
    // Control speed - what user set on slider (0-350 km/h, constant while driving)
    private val controlSpeedKmh = AtomicReference<Float>(40f)
    
    // Actual simulated speed - after curve reduction applied (varies with curves)
    // Example: 40 km/h control × 0.4 curve reduction = 16 km/h actual
    private val actualSpeedKmh = AtomicReference<Float>(0f)
    
    // Current curve reduction factor (0-1) for sensor spoofing sync
    // 1.0 = normal, 0.5 = half speed, 0.3 = sharp turn
    private val currentCurveReduction = AtomicReference<Float>(1f)
    
    /**
     * Update control speed (from UI slider)
     * This is the speed the user set, not affected by curves
     */
    fun setControlSpeed(speed: Float) {
        controlSpeedKmh.set(speed.coerceIn(0f, 350f))
    }
    
    /**
     * Update actual simulation speed from RouteSimulator
     * Called by RouteSimulator during movement simulation (after curve reduction)
     * @param speed The actual speed being used (control speed × curve reduction)
     */
    fun updateActualSpeed(speed: Float) {
        actualSpeedKmh.set(speed.coerceIn(0f, 350f))
    }
    
    /**
     * Update curve reduction factor for sensor sync
     * Called by RouteSimulator when detecting curves
     * @param reduction Reduction factor: 1.0 = normal, 0.5 = half speed, 0.3 = sharp turn
     */
    fun updateCurveReduction(reduction: Float) {
        currentCurveReduction.set(reduction.coerceIn(0f, 1f))
    }
    
    /**
     * Get control speed (what's set on slider)
     * Used by UI to display what user selected
     */
    fun getControlSpeed(): Float {
        return controlSpeedKmh.get() ?: 40f
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
        return savedSpeedKmh.get() ?: 40f
    }
    
    /**
     * Update saved speed when user changes slider
     * This is the "true" speed the user wants, preserved during curve reduction
     */
    fun setSavedSpeed(speed: Float) {
        savedSpeedKmh.set(speed.coerceIn(0f, 350f))
        // Also update control speed for GPS
        setControlSpeed(speed)
    }
    
    /**
     * Reset to default values (when simulation stops)
     */
    fun reset() {
        savedSpeedKmh.set(40f)
        controlSpeedKmh.set(40f)
        actualSpeedKmh.set(0f)
        currentCurveReduction.set(1f)
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
