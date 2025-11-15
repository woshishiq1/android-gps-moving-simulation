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
 * CRITICAL IMPLEMENTATION FOR FULL FLAVOR:
 * This hook intercepts SensorManager calls and provides fake sensor data that matches
 * the spoofed location from RouteSimulator/Joystick. Implements Kalman filtering
 * for accelerometer and gyroscope data to make sensor readings realistic.
 *
 * Detection Bypass:
 * - Banks & ML models cross-check GPS location with accelerometer/gyroscope data
 * - Our Kalman-filtered fake acceleration makes movement consistent with GPS
 * - Magnetometer (compass) data is synchronized with actual bearing of movement
 * - Prevents "GPS jump without sensor support" and "bearing mismatch" detection
 * - Syncs with RouteSimulator update intervals (300ms) for temporal consistency
 *
 * Sensors Spoofed:
 * - TYPE_ACCELEROMETER (1): Kalman-filtered acceleration matching location delta
 * - TYPE_MAGNETIC_FIELD (2): Compass bearing synchronized with movement direction
 * - TYPE_GYROSCOPE (4): Minimal rotation noise to prevent detection
 * - TYPE_GRAVITY (9): Constant gravity with realistic Z-axis bias
 *
 * Scope: ONLY affects app-level hooks (scoped apps)
 * - Package filter: ignores "android" (system) and BuildConfig.APPLICATION_ID (own app)
 * - Does NOT affect system-level spoofing or Wi-Fi SSID changes
 *
 * Technical Details:
 * - Kalman filter for accel: Reduces jitter while maintaining realism
 * - Magnetic field: Synchronized with actual bearing of GPS movement
 * - Gyroscope: Minimal rotation to prevent detection
 * - Sensor timestamps: Synchronized with location update intervals
 * - No excessive acceleration patterns that trigger ML anomaly detection
 */
object SensorSpoofHook {

    private const val TAG = "SensorSpoof"

    // Kalman filter coefficients for accelerometer
    private const val KALMAN_Q = 0.01f  // Process noise (lower = smoother but less responsive)
    private const val KALMAN_R = 0.1f   // Measurement noise (higher = less trusting measurements)

    // State tracking
    private var lastSpoofedLat = 40.0
    private var lastSpoofedLng = 0.0
    private var lastLocationUpdateTime = 0L
    
    // Simulation state tracking for proper reset
    private var lastSyncedSpeed = 0f  // Track if speed changed to detect start/stop

    // Kalman filter state for accelerometer (3 axes)
    private var accelStateX = 0f
    private var accelStateY = 0f
    private var accelStateZ = 9.8f  // Z-axis should be ~9.8m/s² (gravity)

    private var accelErrorX = 1f
    private var accelErrorY = 1f
    private var accelErrorZ = 1f

    // Previous location for delta calculation
    private var prevLat = 40.0
    private var prevLng = 0.0
    private var prevTime = 0L

    // Bearing/Direction tracking
    private var currentBearing = 0f
    private var lastBearingUpdate = 0L
    
    /**
     * Reset all sensor state when simulation stops
     * Called internally when speed transitions to 0
     */
    private fun resetSensorState() {
        accelStateX = 0f
        accelStateY = 0f
        accelStateZ = 9.8f  // Gravity
        
        accelErrorX = 1f
        accelErrorY = 1f
        accelErrorZ = 1f
        
        prevLat = lastSpoofedLat
        prevLng = lastSpoofedLng
        prevTime = System.currentTimeMillis()
        
        lastBearingUpdate = 0L
        currentBearing = 0f
        
        lastSyncedSpeed = 0f
    }

    fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Only hook at app level (scoped apps)
        // Skip system package and own app
        if (lpparam.packageName == "android" || lpparam.packageName == BuildConfig.APPLICATION_ID) {
            return
        }

        try {
            // Update location reference from LocationHook
            updateLocationReference()

            // Hook SensorManager.registerListener()
            hookSensorManagerRegisterListener(lpparam)

            // Hook SensorEvent creation for direct sensor data injection
            hookSensorEvent(lpparam)

            XposedBridge.log("$TAG: Initialized for package ${lpparam.packageName}")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to initialize: ${e.message}")
        }
    }

    /**
     * Hook SensorManager.registerListener() to inject fake sensor data
     * This is the primary path for sensor data in Android
     */
    private fun hookSensorManagerRegisterListener(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val sensorManagerClass = XposedHelpers.findClass(
                "android.hardware.SensorManager",
                lpparam.classLoader
            )

            val sensorClass = XposedHelpers.findClass(
                "android.hardware.Sensor",
                lpparam.classLoader
            )

            val sensorEventListenerClass = XposedHelpers.findClass(
                "android.hardware.SensorEventListener",
                lpparam.classLoader
            )

            // Hook registerListener with multiple parameter combinations
            val registerMethods = arrayOf(
                arrayOf(sensorEventListenerClass, sensorClass, Int::class.java),
                arrayOf(sensorEventListenerClass, sensorClass, Int::class.java, Int::class.java),
                arrayOf(sensorEventListenerClass, sensorClass, Int::class.java, Int::class.java, Int::class.java)
            )

            for (params in registerMethods) {
                try {
                    XposedHelpers.findAndHookMethod(
                        sensorManagerClass,
                        "registerListener",
                        *params,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val sensorType = try {
                                    val sensor = param.args[1]
                                    XposedHelpers.getIntField(sensor, "mType")
                                } catch (e: Throwable) {
                                    -1
                                }

                                // We'll hook the listener callback mechanism instead
                                // This allows us to intercept onSensorChanged calls
                                if (sensorType in arrayOf(
                                    1,  // TYPE_ACCELEROMETER
                                    2,  // TYPE_MAGNETIC_FIELD (compass/bearing)
                                    4,  // TYPE_GYROSCOPE
                                    5,  // TYPE_LIGHT
                                    9   // TYPE_GRAVITY
                                )) {
                                    XposedBridge.log("$TAG: Registered listener for sensor type $sensorType")
                                }
                            }
                        }
                    )
                } catch (e: Throwable) {
                    // Ignore - not all parameter combinations may exist
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook SensorManager.registerListener: ${e.message}")
        }
    }

    /**
     * Hook SensorEvent to inject fake sensor data
     * This intercepts the actual sensor event object before listener receives it
     */
    private fun hookSensorEvent(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val sensorEventClass = XposedHelpers.findClassIfExists(
                "android.hardware.SensorEvent",
                lpparam.classLoader
            ) ?: return

            // Hook the values array getter/setter
            for (field in sensorEventClass.declaredFields) {
                if (field.name == "values" && field.type == FloatArray::class.java) {
                    // We need to intercept at the listener level instead
                    // Hook the sensor event listener callback
                    hookSensorEventListener(lpparam)
                    break
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook SensorEvent: ${e.message}")
        }
    }

    /**
     * Hook SensorEventListener.onSensorChanged() to modify sensor data
     * This is where we inject fake sensor values
     */
    private fun hookSensorEventListener(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val sensorEventListenerClass = XposedHelpers.findClassIfExists(
                "android.hardware.SensorEventListener",
                lpparam.classLoader
            ) ?: return

            val sensorEventClass = XposedHelpers.findClassIfExists(
                "android.hardware.SensorEvent",
                lpparam.classLoader
            ) ?: return

            // Hook the onSensorChanged method
            XposedHelpers.findAndHookMethod(
                sensorEventListenerClass,
                "onSensorChanged",
                sensorEventClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val sensorEvent = param.args[0]
                            val sensorType = XposedHelpers.getIntField(sensorEvent, "sensor.mType")
                            val values = XposedHelpers.getObjectField(sensorEvent, "values") as? FloatArray ?: return

                            // Inject fake sensor data based on location spoofing
                            when (sensorType) {
                                1 -> {  // TYPE_ACCELEROMETER
                                    injectAccelerometerData(values)
                                }
                                2 -> {  // TYPE_MAGNETIC_FIELD (compass bearing)
                                    injectMagneticFieldData(values)
                                }
                                4 -> {  // TYPE_GYROSCOPE
                                    injectGyroscopeData(values)
                                }
                                9 -> {  // TYPE_GRAVITY
                                    injectGravityData(values)
                                }
                            }
                        } catch (e: Throwable) {
                            // Silently fail - don't break sensor functionality
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook SensorEventListener: ${e.message}")
        }
    }

    /**
     * Inject fake accelerometer data synchronized with spoofed location
     * Uses Kalman filtering to maintain realistic acceleration patterns
     *
     * Calculation:
     * - Compute delta lat/lng from last location
     * - Convert to acceleration (distance / time²)
     * - Apply Kalman filter for smoothing
     * - Add minimal noise for realism
     */
    private fun injectAccelerometerData(values: FloatArray) {
        try {
            updateLocationReference()

            // Get actual speed from SpeedSyncManager (includes curve reduction)
            val actualSpeed = SpeedSyncManager.getActualSpeed()
            
            // Auto-reset sensor state when simulation stops
            if (actualSpeed <= 0.01f && lastSyncedSpeed > 0.01f) {
                resetSensorState()
            }
            lastSyncedSpeed = actualSpeed

            // Calculate time delta in seconds
            val currentTime = System.currentTimeMillis()
            val timeDeltaSec = maxOf(0.1f, (currentTime - prevTime) / 1000f)

            // Calculate delta position in meters from actual location
            val deltaMeters = calculateDistanceMeters(prevLat, prevLng, lastSpoofedLat, lastSpoofedLng)

            // Get curve reduction factor from SpeedSyncManager
            // On curves, acceleration is reduced proportionally
            val curveReduction = SpeedSyncManager.getCurveReduction()

            // CRITICAL FIX: Calculate acceleration using ACTUAL SPEED to ensure sensor data matches location data
            // Expected distance = actualSpeed (km/h) × time (seconds) / 3600 × 1000
            val expectedDistanceMeters = if (actualSpeed > 0.01f && timeDeltaSec > 0) {
                // Convert km/h to m/s: km/h × 1000m/km ÷ 3600s/h = m/s
                val speedMs = actualSpeed * 1000f / 3600f
                speedMs * timeDeltaSec
            } else {
                0f
            }

            // Use actual distance from location (which already has curve reduction baked in)
            // If there's significant mismatch, it indicates timing or synchronization issue
            val distanceForAccel = if (deltaMeters > 0.01f) deltaMeters else expectedDistanceMeters

            // Calculate acceleration: a = 2 * distance / time²
            // Using actual movement distance to ensure sensor matches location
            var accelMagnitude = if (timeDeltaSec > 0 && distanceForAccel > 0) {
                (2f * distanceForAccel / (timeDeltaSec * timeDeltaSec)).coerceIn(-50f, 50f)
            } else {
                0f
            }

            // Curve reduction already applied to location position, so acceleration matches
            // Don't apply curve reduction again - it's already in the distance calculation
            // accelMagnitude is already consistent with actual speed

            // Distribute acceleration across 3 axes realistically
            // X and Y axes contain horizontal acceleration
            // Z axis contains gravity (9.8 m/s²) plus vertical component

            val accelX = (accelMagnitude * 0.4f) + (Math.random() * 0.5 - 0.25).toFloat()
            val accelY = (accelMagnitude * 0.4f) + (Math.random() * 0.5 - 0.25).toFloat()
            val accelZ = 9.8f + (accelMagnitude * 0.1f) + (Math.random() * 0.3 - 0.15).toFloat()

            // Apply Kalman filter to smooth values
            val filteredX = kalmanFilter(accelX, accelStateX, accelErrorX)
            val filteredY = kalmanFilter(accelY, accelStateY, accelErrorY)
            val filteredZ = kalmanFilter(accelZ, accelStateZ, accelErrorZ)

            accelStateX = filteredX
            accelStateY = filteredY
            accelStateZ = filteredZ

            // Update values array
            if (values.size >= 3) {
                values[0] = filteredX
                values[1] = filteredY
                values[2] = filteredZ
            }

            prevLat = lastSpoofedLat
            prevLng = lastSpoofedLng
            prevTime = currentTime

        } catch (e: Throwable) {
            // Silently fail
        }
    }

    /**
     * Inject fake gyroscope data (rotation rates)
     * Gyroscope should show minimal rotation since we're moving in a line
     * On curves, rotation increases proportionally to turn angle
     */
    private fun injectGyroscopeData(values: FloatArray) {
        try {
            // Get curve reduction factor
            // When turning (low curveReduction), gyroscope should show rotation
            val curveReduction = SpeedSyncManager.getCurveReduction()

            // Gyroscope: minimal rotation on straight roads, more rotation on curves
            // curveReduction: 1.0 (straight) → 0.3 (sharp turn)
            // rotation: should be inverse - high turn → high rotation
            val turnFactor = (1.0f - curveReduction)  // 0 on straight, 0.7 on sharp turn

            // Small random noise for realism
            // Increase rotation noise on curves for realism
            val noiseX = ((Math.random() * 0.1 - 0.05) * (0.3f + turnFactor)).toFloat()
            val noiseY = ((Math.random() * 0.1 - 0.05) * (0.3f + turnFactor)).toFloat()
            val noiseZ = ((Math.random() * 0.15 - 0.075) * (0.5f + turnFactor * 1.5f)).toFloat()  // Z-axis rotation for turning

            if (values.size >= 3) {
                values[0] = noiseX
                values[1] = noiseY
                values[2] = noiseZ
            }
        } catch (e: Throwable) {
            // Silently fail
        }
    }

    /**
     * Inject fake magnetic field data (compass bearing)
     * Magnetic field values are used by apps to calculate compass direction
     * We sync this with the actual bearing of movement from LocationHook
     *
     * Magnetic field vector = [Bx, By, Bz] representing Earth's magnetic field
     * The bearing is calculated from Bx and By components
     */
    private fun injectMagneticFieldData(values: FloatArray) {
        try {
            // Calculate current bearing from location movement
            updateLocationReference()
            val currentTime = System.currentTimeMillis()

            // Only update bearing if enough time has passed and position changed
            if (currentTime - lastBearingUpdate > 500) {
                val deltaLat = lastSpoofedLat - prevLat
                val deltaLng = lastSpoofedLng - prevLng

                if (deltaLat != 0.0 || deltaLng != 0.0) {
                    // Calculate bearing using atan2 (0-360 degrees)
                    // bearing = atan2(deltaLng, deltaLat)
                    val bearingRad = atan2(deltaLng, deltaLat)
                    currentBearing = (Math.toDegrees(bearingRad).toFloat() + 360) % 360
                    lastBearingUpdate = currentTime

                    // Verify bearing sync: Compare with SpeedSyncManager bearing
                    val syncedBearing = SpeedSyncManager.getBearing()
                    val bearingDiff = kotlin.math.abs(currentBearing - syncedBearing)
                    XposedBridge.log("$TAG: Bearing - Magnetometer: ${currentBearing}°, Synced: ${syncedBearing}°, Diff: ${bearingDiff}°")
                }
            }

            // Convert bearing angle to magnetic field vector
            // Earth's magnetic field in μT (typical strength ~50 μT)
            val magneticFieldStrength = 50f
            val bearingRad = Math.toRadians(currentBearing.toDouble())

            // Bx = B * sin(bearing)
            // By = B * cos(bearing)
            // Bz = small vertical component (varies by location, typically 20-30 μT)

            if (values.size >= 3) {
                values[0] = (magneticFieldStrength * sin(bearingRad)).toFloat()  // Bx
                values[1] = (magneticFieldStrength * cos(bearingRad)).toFloat()  // By
                values[2] = 30f + (Math.random() * 10 - 5).toFloat()             // Bz (vertical component)

                // Add minimal noise for realism
                values[0] += (Math.random() * 2 - 1).toFloat()
                values[1] += (Math.random() * 2 - 1).toFloat()
                values[2] += (Math.random() * 2 - 1).toFloat()
            }
        } catch (e: Throwable) {
            // Silently fail
        }
    }

    /**
     * Inject fake gravity data (acceleration due to gravity)
     * Gravity should be stable at 9.8 m/s² pointing downward
     */
    private fun injectGravityData(values: FloatArray) {
        try {
            // Gravity is typically just the Z-axis pointing down with minimal XY
            if (values.size >= 3) {
                values[0] = 0.1f + (Math.random() * 0.1 - 0.05).toFloat()
                values[1] = 0.1f + (Math.random() * 0.1 - 0.05).toFloat()
                values[2] = 9.8f + (Math.random() * 0.2 - 0.1).toFloat()
            }
        } catch (e: Throwable) {
            // Silently fail
        }
    }

    /**
     * Kalman filter implementation for smooth sensor data
     * Reduces sensor jitter while maintaining responsiveness
     */
    private fun kalmanFilter(measurement: Float, state: Float, error: Float): Float {
        // Prediction step
        val predictedState = state
        val predictedError = error + KALMAN_Q

        // Update step
        val kalmanGain = predictedError / (predictedError + KALMAN_R)
        val newState = predictedState + kalmanGain * (measurement - predictedState)
        val newError = (1f - kalmanGain) * predictedError

        return newState
    }

    /**
     * Calculate distance between two lat/lng points in meters
     * Using haversine formula (same as PolylineUtils)
     */
    private fun calculateDistanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val R = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (R * c).toFloat()
    }

    /**
     * Update location reference from LocationHook
     * Gets the current spoofed location being used by the app
     */
    private fun updateLocationReference() {
        try {
            lastSpoofedLat = LocationHook.newlat
            lastSpoofedLng = LocationHook.newlng
            lastLocationUpdateTime = System.currentTimeMillis()
        } catch (e: Throwable) {
            // Location not available yet
        }
    }
}
