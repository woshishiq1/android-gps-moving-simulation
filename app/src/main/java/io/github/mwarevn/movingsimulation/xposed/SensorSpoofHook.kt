package io.github.mwarevn.movingsimulation.xposed

import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.mwarevn.movingsimulation.BuildConfig
import io.github.mwarevn.movingsimulation.utils.SpeedSyncManager
import kotlin.math.*

/**
 * SensorSpoofHook - Advanced sensor spoofing for ML-based GPS detection bypass
 *
 * CRITICAL IMPLEMENTATION FOR FULL BYPASS:
 * Hooks into SystemSensorManager (concrete class) to intercept and modify sensor data
 * in real-time. This ensures magnetometer (compass) matches GPS bearing 100%.
 *
 * Detection Bypass:
 * - Banks & ML models cross-check GPS location with sensor data
 * - Our fake magnetometer makes compass match GPS bearing perfectly
 * - Prevents "bearing mismatch" detection
 *
 * Sensors Spoofed:
 * - TYPE_MAGNETIC_FIELD (2): Compass bearing synchronized with movement direction
 * - TYPE_ACCELEROMETER (1): Kalman-filtered acceleration matching location delta
 * - TYPE_GYROSCOPE (4): Minimal rotation noise to prevent detection
 * - TYPE_GRAVITY (9): Constant gravity with realistic Z-axis bias
 */
object SensorSpoofHook {

    private const val TAG = "SensorSpoof"

    // Kalman filter coefficients for accelerometer
    private const val KALMAN_Q = 0.01f
    private const val KALMAN_R = 0.1f

    // State tracking
    private var lastSpoofedLat = 40.0
    private var lastSpoofedLng = 0.0
    private var lastLocationUpdateTime = 0L
    
    private var lastSyncedSpeed = 0f

    // Kalman filter state for accelerometer
    private var accelStateX = 0f
    private var accelStateY = 0f
    private var accelStateZ = 9.8f

    private var accelErrorX = 1f
    private var accelErrorY = 1f
    private var accelErrorZ = 1f

    // Previous location for delta calculation
    private var prevLat = 40.0
    private var prevLng = 0.0
    private var prevTime = 0L

    // Bearing tracking
    private var currentBearing = 0f
    
    private fun resetSensorState() {
        accelStateX = 0f
        accelStateY = 0f
        accelStateZ = 9.8f
        
        accelErrorX = 1f
        accelErrorY = 1f
        accelErrorZ = 1f
        
        prevLat = lastSpoofedLat
        prevLng = lastSpoofedLng
        prevTime = System.currentTimeMillis()
        
        currentBearing = 0f
        lastSyncedSpeed = 0f
    }

    fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Skip system package and own app
        if (lpparam.packageName == "android" || lpparam.packageName == BuildConfig.APPLICATION_ID) {
            return
        }

        try {
            // Update location reference
            updateLocationReference()

            // Hook SystemSensorManager (concrete implementation)
            hookSystemSensorManager(lpparam)

            XposedBridge.log("$TAG: Initialized for package ${lpparam.packageName}")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to initialize: ${e.message}")
        }
    }

    /**
     * CRITICAL FIX: Hook SystemSensorManager's dispatch method
     * This is the concrete implementation that actually dispatches sensor events
     */
    private fun hookSystemSensorManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val systemSensorManagerClass = XposedHelpers.findClassIfExists(
                "android.hardware.SystemSensorManager",
                lpparam.classLoader
            )
            
            if (systemSensorManagerClass == null) {
                XposedBridge.log("$TAG: SystemSensorManager not found, trying SensorManager")
                return
            }
            
            // Hook all methods to find the one that dispatches sensor data
            for (method in systemSensorManagerClass.declaredMethods) {
                val methodName = method.name
                
                // Look for dispatch/notify methods
                if (methodName.contains("dispatch", ignoreCase = true) ||
                    methodName.contains("handleMessage", ignoreCase = true)) {
                    
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                // Check if GPS spoofing is active
                                val settings = Xshare()
                                if (!settings.isStarted) return
                                
                                // Search for SensorEvent in method arguments
                                for (arg in param.args) {
                                    if (arg == null) continue
                                    
                                    if (arg.javaClass.simpleName == "SensorEvent") {
                                        try {
                                            val sensor = XposedHelpers.getObjectField(arg, "sensor")
                                            val sensorType = XposedHelpers.getIntField(sensor, "mType")
                                            val values = XposedHelpers.getObjectField(arg, "values") as? FloatArray
                                            
                                            if (values != null && values.isNotEmpty()) {
                                                // INJECT FAKE SENSOR DATA
                                                when (sensorType) {
                                                    2 -> {  // TYPE_MAGNETIC_FIELD - MOST IMPORTANT!
                                                        injectMagneticFieldData(values)
                                                    }
                                                    1 -> {  // TYPE_ACCELEROMETER
                                                        injectAccelerometerData(values)
                                                    }
                                                    4 -> {  // TYPE_GYROSCOPE
                                                        injectGyroscopeData(values)
                                                    }
                                                    9 -> {  // TYPE_GRAVITY
                                                        injectGravityData(values)
                                                    }
                                                }
                                            }
                                        } catch (e: Throwable) {
                                            // Skip this event
                                        }
                                    }
                                }
                            } catch (e: Throwable) {
                                // Never crash the sensor system
                            }
                        }
                    })
                    
                    XposedBridge.log("$TAG: Hooked SystemSensorManager.${methodName}")
                }
            }
            
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook SystemSensorManager: ${e.message}")
        }
    }

    /**
     * Update location reference from LocationHook
     */
    private fun updateLocationReference() {
        try {
            lastSpoofedLat = LocationHook.newlat
            lastSpoofedLng = LocationHook.newlng
            lastLocationUpdateTime = System.currentTimeMillis()
        } catch (e: Throwable) {
            // LocationHook not initialized yet
        }
    }

    /**
     * CRITICAL: Inject fake accelerometer data
     */
    private fun injectAccelerometerData(values: FloatArray) {
        try {
            updateLocationReference()
            
            val actualSpeed = SpeedSyncManager.getActualSpeed()
            val curveReduction = SpeedSyncManager.getCurveReduction()
            
            // Check if simulation started/stopped
            if (actualSpeed < 0.01f && lastSyncedSpeed >= 0.01f) {
                resetSensorState()
            }
            lastSyncedSpeed = actualSpeed
            
            val currentTime = System.currentTimeMillis()
            val deltaTime = (currentTime - prevTime) / 1000.0
            
            if (deltaTime > 0 && deltaTime < 10.0 && actualSpeed > 0.01f) {
                val bearing = SpeedSyncManager.getBearing()
                val bearingRad = Math.toRadians(bearing.toDouble())
                
                // Base acceleration magnitude from speed
                val baseAccelMagnitude = (actualSpeed / 3.6) * 0.5
                
                // Adjust for curves (higher accel on curves)
                val curveAccelFactor = 1.0 + ((1.0 - curveReduction) * 2.0)
                val accelMagnitude = baseAccelMagnitude * curveAccelFactor
                
                // Distribute acceleration along bearing direction
                val accelX = (accelMagnitude * sin(bearingRad)).toFloat()
                val accelY = (accelMagnitude * cos(bearingRad)).toFloat()
                
                // Apply Kalman filter for smooth data
                val kalmanGainX = accelErrorX / (accelErrorX + KALMAN_R)
                val kalmanGainY = accelErrorY / (accelErrorY + KALMAN_R)
                
                accelStateX += kalmanGainX * (accelX - accelStateX)
                accelStateY += kalmanGainY * (accelY - accelStateY)
                
                accelErrorX = (1 - kalmanGainX) * accelErrorX + KALMAN_Q
                accelErrorY = (1 - kalmanGainY) * accelErrorY + KALMAN_Q
                
                // Add realistic noise
                val noiseX = (Math.random() * 0.3 - 0.15).toFloat()
                val noiseY = (Math.random() * 0.3 - 0.15).toFloat()
                val noiseZ = (Math.random() * 0.2 - 0.1).toFloat()
                
                if (values.size >= 3) {
                    values[0] = accelStateX + noiseX
                    values[1] = accelStateY + noiseY
                    values[2] = 9.8f + noiseZ  // Gravity + small variation
                }
            }
            
            prevTime = currentTime
            
        } catch (e: Throwable) {
            // Silently fail
        }
    }

    /**
     * CRITICAL: Inject fake magnetometer data
     * This is THE MOST IMPORTANT for bearing spoofing!
     */
    private fun injectMagneticFieldData(values: FloatArray) {
        try {
            // Get bearing DIRECTLY from SpeedSyncManager
            val syncedBearing = SpeedSyncManager.getBearing()
            val actualSpeed = SpeedSyncManager.getActualSpeed()
            
            // ALWAYS use synced bearing (even when speed = 0)
            currentBearing = syncedBearing
            
            // Convert bearing to magnetic field vector
            val magneticFieldStrength = 50f  // μT
            val bearingRad = Math.toRadians(currentBearing.toDouble())
            
            // Android compass formula
            if (values.size >= 3) {
                values[0] = (magneticFieldStrength * sin(bearingRad)).toFloat()  // Bx (East)
                values[1] = (magneticFieldStrength * cos(bearingRad)).toFloat()  // By (North)
                values[2] = 30f + (Math.random() * 10 - 5).toFloat()             // Bz (Down)
                
                // Add MINIMAL noise
                values[0] += (Math.random() * 1.5 - 0.75).toFloat()
                values[1] += (Math.random() * 1.5 - 0.75).toFloat()
                values[2] += (Math.random() * 2 - 1).toFloat()
            }
            
            // Debug log
            if (actualSpeed > 0.01f && System.currentTimeMillis() % 5000L < 100L) {
                XposedBridge.log("$TAG: Magnetometer INJECTED - Bearing=${String.format("%.1f", currentBearing)}° Speed=${String.format("%.1f", actualSpeed)} km/h")
            }
            
        } catch (e: Throwable) {
            // Silently fail
        }
    }

    /**
     * Inject fake gyroscope data
     */
    private fun injectGyroscopeData(values: FloatArray) {
        try {
            val actualSpeed = SpeedSyncManager.getActualSpeed()
            val curveReduction = SpeedSyncManager.getCurveReduction()
            
            if (actualSpeed > 0.01f) {
                val turnFactor = 1.0 - curveReduction.toDouble()
                val baseRotationRate = (turnFactor * actualSpeed * 0.02).toFloat()
                
                val gyroZ = baseRotationRate  // Yaw rotation
                val noiseX = (Math.random() * 0.02 - 0.01).toFloat()
                val noiseY = (Math.random() * 0.02 - 0.01).toFloat()
                val noiseZ = (Math.random() * 0.03 - 0.015).toFloat()
                
                if (values.size >= 3) {
                    values[0] = noiseX
                    values[1] = noiseY
                    values[2] = gyroZ + noiseZ
                }
            } else {
                // Stationary - minimal noise
                if (values.size >= 3) {
                    values[0] = (Math.random() * 0.01 - 0.005).toFloat()
                    values[1] = (Math.random() * 0.01 - 0.005).toFloat()
                    values[2] = (Math.random() * 0.01 - 0.005).toFloat()
                }
            }
        } catch (e: Throwable) {
            // Silently fail
        }
    }

    /**
     * Inject fake gravity data
     */
    private fun injectGravityData(values: FloatArray) {
        try {
            if (values.size >= 3) {
                values[0] = 0.1f + (Math.random() * 0.1 - 0.05).toFloat()
                values[1] = 0.1f + (Math.random() * 0.1 - 0.05).toFloat()
                values[2] = 9.8f + (Math.random() * 0.2 - 0.1).toFloat()
            }
        } catch (e: Throwable) {
            // Silently fail
        }
    }
}
