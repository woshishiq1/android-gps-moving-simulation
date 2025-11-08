package io.github.mwarevn.fakegpsmoving.utils

import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.*
import kotlin.random.Random

/**
 * Simulates movement along a polyline (list of LatLng) at a given speed (km/h).
 * Provides position updates via callback - GPS mocking is handled by the caller
 * using the same method as the "Set Location" feature for consistency and stealth.
 * 
 * Enhanced with realistic motorbike movement patterns:
 * - Variable speed simulation (±10% speed variation)
 * - Natural GPS drift and micro-movements
 * - Realistic pause/acceleration patterns
 */
class RouteSimulator(
    private val points: List<LatLng>,
    private var speedKmh: Double = 45.0, // Default motorbike speed
    private val updateIntervalMs: Long = 150L,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {

    private var job: Job? = null
    private var paused: Boolean = false
    private val random = Random.Default

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
                
                // Variable speed for realistic motorbike movement (±10% variation)
                val speedVariation = random.nextDouble(0.9, 1.1)
                val currentSpeedKmh = speedKmh * speedVariation
                val speedMs = currentSpeedKmh * 1000.0 / 3600.0
                
                var traveled = 0.0
                val stepMeters = speedMs * (updateIntervalMs.toDouble() / 1000.0)
                
                while (traveled < segMeters && isActive) {
                    if (paused) {
                        delay(updateIntervalMs)
                        continue
                    }
                    
                    val frac = (traveled / segMeters).coerceIn(0.0, 1.0)
                    val baseLat = a.latitude + (b.latitude - a.latitude) * frac
                    val baseLng = a.longitude + (b.longitude - a.longitude) * frac
                    
                    // Add small random variations for natural GPS drift (±2-5 meters)
                    val driftDistance = random.nextDouble(2.0, 5.0) // meters
                    val driftAngle = random.nextDouble(0.0, 2 * Math.PI)
                    val driftLat = driftDistance * Math.cos(driftAngle) / 111320.0 // ~111320 meters per degree latitude
                    val driftLng = driftDistance * Math.sin(driftAngle) / (111320.0 * Math.cos(Math.toRadians(baseLat)))
                    
                    val pos = LatLng(
                        baseLat + driftLat,
                        baseLng + driftLng
                    )
                    
                    onPosition(pos)
                    traveled += stepMeters
                    
                    // Occasional micro-pauses for realistic traffic/signals (5% chance)
                    if (random.nextDouble() < 0.05) {
                        delay(updateIntervalMs + random.nextLong(500, 2000))
                    } else {
                        delay(updateIntervalMs)
                    }
                }
                
                // ensure arrive at destination point (with slight drift)
                val finalDriftLat = random.nextDouble(-3.0, 3.0) / 111320.0
                val finalDriftLng = random.nextDouble(-3.0, 3.0) / (111320.0 * Math.cos(Math.toRadians(b.latitude)))
                onPosition(LatLng(b.latitude + finalDriftLat, b.longitude + finalDriftLng))
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
