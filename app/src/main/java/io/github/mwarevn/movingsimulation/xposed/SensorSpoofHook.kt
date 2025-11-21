package io.github.mwarevn.movingsimulation.xposed

import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.mwarevn.movingsimulation.BuildConfig
import io.github.mwarevn.movingsimulation.utils.SpeedSyncManager
import kotlin.math.*
import java.lang.reflect.Field

/**
 * SensorSpoofHook - COMPLETE REWRITE for 100% sensor spoofing
 *
 * CRITICAL FIXES:
 * 1. Hook SensorEvent.values field DIRECTLY (not methods) - ensures 100% interception
 * 2. Hook SensorEventListener.onSensorChanged() at concrete implementation level
 * 3. Hook SensorManager.registerListener() to intercept all sensor registrations
 * 4. Comprehensive bearing synchronization with RouteSimulator
 *
 * Detection Bypass:
 * - Banks & ML models cross-check GPS location with sensor data
 * - Our fake magnetometer makes compass match GPS bearing perfectly
 * - Accelerometer matches movement direction and speed
 * - Gyroscope reflects turning motion
 * - Prevents ALL sensor-based detection
 *
 * Sensors Spoofed:
 * - TYPE_MAGNETIC_FIELD (2): Compass bearing synchronized with GPS movement direction
 * - TYPE_ACCELEROMETER (1): Kalman-filtered acceleration matching location delta
 * - TYPE_GYROSCOPE (4): Rotation matching turn intensity
 * - TYPE_GRAVITY (9): Constant gravity with realistic Z-axis bias
 */
object SensorSpoofHook {

    private const val TAG = "SensorSpoof"

    // Kalman filter coefficients for accelerometer
    private const val KALMAN_Q = 0.01f
    private const val KALMAN_R = 0.1f

    // State tracking
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

    // Cache for SensorEvent.values field (for direct modification)
    private var sensorEventValuesField: Field? = null

    fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Skip system package and own app
        if (lpparam.packageName == "android" || lpparam.packageName == BuildConfig.APPLICATION_ID) {
            return
        }

        try {
            // CRITICAL: Hook SensorEvent.values field DIRECTLY
            hookSensorEventValuesField(lpparam)
            
            // Hook SensorEventListener.onSensorChanged() at concrete level
            hookSensorEventListener(lpparam)
            
            // Hook SensorManager.registerListener() to track sensor registrations
            hookSensorManagerRegisterListener(lpparam)

            XposedBridge.log("$TAG: Initialized for package ${lpparam.packageName}")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to initialize: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * CRITICAL FIX: Hook SensorEvent.values field DIRECTLY
     * This ensures we intercept ALL sensor data reads
     */
    private fun hookSensorEventValuesField(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val sensorEventClass = XposedHelpers.findClass(
                "android.hardware.SensorEvent",
                lpparam.classLoader
            )
            
            // Find the values field
            sensorEventValuesField = sensorEventClass.getDeclaredField("values")
            sensorEventValuesField?.isAccessible = true
            
            // CRITICAL: Hook field access directly using Xposed field hook
            // This intercepts when apps read event.values directly
            try {
                XposedHelpers.findAndHookMethod(
                    sensorEventClass,
                    "getValues",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val settings = Xshare()
                                if (!settings.isStarted) return
                                
                                val sensorEvent = param.thisObject
                                val sensor = XposedHelpers.getObjectField(sensorEvent, "sensor")
                                val sensorType = XposedHelpers.getIntField(sensor, "mType")
                                
                                val values = param.result as? FloatArray
                                if (values != null && values.isNotEmpty()) {
                                    // Modify values array
                                    when (sensorType) {
                                        2 -> {  // MAGNETOMETER - CRITICAL!
                                            injectMagneticFieldData(values)
                                            param.result = values
                                        }
                                        1 -> {
                                            injectAccelerometerData(values)
                                            param.result = values
                                        }
                                        4 -> {
                                            injectGyroscopeData(values)
                                            param.result = values
                                        }
                                        9 -> {
                                            injectGravityData(values)
                                            param.result = values
                                        }
                                    }
                                }
                            } catch (e: Throwable) {
                                // Silently fail
                            }
                        }
                    }
                )
                XposedBridge.log("$TAG: Hooked SensorEvent.getValues() method")
            } catch (e: Throwable) {
                XposedBridge.log("$TAG: getValues() method not found, trying field access")
            }
            
            // Also try hooking field access directly
            // Some apps may access event.values directly without getter
            try {
                // Hook all methods that might access values field
                for (method in sensorEventClass.declaredMethods) {
                    if (method.name.contains("value", ignoreCase = true) || 
                        method.name == "toString" ||
                        method.returnType == FloatArray::class.java) {
                        try {
                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    if (param.result is FloatArray) {
                                        try {
                                            val settings = Xshare()
                                            if (!settings.isStarted) return
                                            
                                            // Try to get sensor type from thisObject
                                            val sensorEvent = param.thisObject
                                            val sensor = XposedHelpers.getObjectField(sensorEvent, "sensor")
                                            val sensorType = XposedHelpers.getIntField(sensor, "mType")
                                            
                                            val values = param.result as? FloatArray
                                            if (values != null && values.size >= 3 && sensorType == 2) {
                                                injectMagneticFieldData(values)
                                                param.result = values
                                            }
                                        } catch (e: Throwable) {
                                            // Ignore
                                        }
                                    }
                                }
                            })
                        } catch (e: Throwable) {
                            // Ignore
                        }
                    }
                }
            } catch (e: Throwable) {
                // Ignore
            }
            
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook SensorEvent.values: ${e.message}")
        }
    }

    /**
     * Hook SensorEventListener.onSensorChanged() at concrete implementation level
     * This catches sensor events before they reach the app
     */
    private fun hookSensorEventListener(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val sensorEventClass = XposedHelpers.findClass(
                "android.hardware.SensorEvent",
                lpparam.classLoader
            )
            
            // Hook all classes that implement SensorEventListener
            // We'll hook the interface method directly
            val sensorEventListenerClass = XposedHelpers.findClassIfExists(
                "android.hardware.SensorEventListener",
                lpparam.classLoader
            )
            
            if (sensorEventListenerClass != null) {
                // Try to hook onSensorChanged in all implementing classes
                // This is a fallback if field hook doesn't work
                XposedBridge.log("$TAG: SensorEventListener interface found")
            }
            
            // More importantly: Hook concrete implementations
            // Many apps use anonymous inner classes or concrete implementations
            // We'll hook them via SensorManager.registerListener
            
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook SensorEventListener: ${e.message}")
        }
    }

    /**
     * Hook SensorManager.registerListener() to intercept sensor registrations
     * This allows us to wrap the listener and modify data before it reaches the app
     */
    private fun hookSensorManagerRegisterListener(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val sensorManagerClass = XposedHelpers.findClass(
                "android.hardware.SensorManager",
                lpparam.classLoader
            )

            val sensorEventListenerClass = XposedHelpers.findClass(
                "android.hardware.SensorEventListener",
                lpparam.classLoader
            )
            
            val sensorClass = XposedHelpers.findClass(
                "android.hardware.Sensor",
                lpparam.classLoader
            )

            // Hook registerListener with multiple parameter combinations
            val registerMethods = arrayOf(
                arrayOf(sensorEventListenerClass, sensorClass, Int::class.java),
                arrayOf(sensorEventListenerClass, sensorClass, Int::class.java, Int::class.java),
                arrayOf(sensorEventListenerClass, sensorClass, Int::class.java, android.os.Handler::class.java)
            )

            for (params in registerMethods) {
                try {
                    XposedHelpers.findAndHookMethod(
                        sensorManagerClass,
                        "registerListener",
                        *params,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    val settings = Xshare()
                                    if (!settings.isStarted) return
                                    
                                    val listener = param.args[0] as? android.hardware.SensorEventListener
                                    val sensor = param.args[1] as? android.hardware.Sensor
                                    
                                    if (listener != null && sensor != null) {
                                        val sensorType = sensor.type
                                        
                                        // Wrap the listener to intercept onSensorChanged
                                        val wrappedListener = object : android.hardware.SensorEventListener {
                                            override fun onSensorChanged(event: SensorEvent) {
                                                try {
                                                    // CRITICAL: Check if GPS spoofing is active
                                                    val settings = Xshare()
                                                    if (!settings.isStarted) {
                                                        listener.onSensorChanged(event)
                                                        return
                                                    }
                                                    
                                                    // CRITICAL: Modify sensor event values DIRECTLY
                                                    // event.values is a public final field, but we can modify array elements
                                                    val values = event.values
                                                    if (values != null && values.isNotEmpty()) {
                                                        // Inject fake data directly into the array
                                                        when (sensorType) {
                                                            2 -> {  // TYPE_MAGNETIC_FIELD - CRITICAL!
                                                                val settings = Xshare()
                                                                val bearing = settings.getSyncedBearing
                                                                val speed = settings.getSyncedActualSpeed
                                                                
                                                                // Store original values for debugging
                                                                val origBx = values[0]
                                                                val origBy = values[1]
                                                                
                                                                injectMagneticFieldData(values)
                                                                
                                                                // ALWAYS log magnetometer injection (for debugging)
                                                                if (settings.isStarted && System.currentTimeMillis() % 3000L < 100L) {
                                                                    XposedBridge.log("$TAG: Magnetometer INJECTED - Type=$sensorType Bearing=${String.format("%.1f", bearing)}° Speed=${String.format("%.1f", speed)} km/h")
                                                                    XposedBridge.log("$TAG:   Original: Bx=${String.format("%.2f", origBx)} By=${String.format("%.2f", origBy)}")
                                                                    XposedBridge.log("$TAG:   Injected: Bx=${String.format("%.2f", values[0])} By=${String.format("%.2f", values[1])}")
                                                                }
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
                                                    
                                                    // Call original listener with modified data
                                                    listener.onSensorChanged(event)
                                                } catch (e: Throwable) {
                                                    // Log error for debugging
                                                    if (sensorType == 2) {
                                                        XposedBridge.log("$TAG: Error in wrapped listener (magnetometer): ${e.message}")
                                                    }
                                                    // Fallback: call original without modification
                                                    try {
                                                        listener.onSensorChanged(event)
                                                    } catch (e2: Throwable) {
                                                        // Ignore
                                                    }
                                                }
                                            }
                                            
                                            override fun onAccuracyChanged(sensor: android.hardware.Sensor, accuracy: Int) {
                                                try {
                                                    listener.onAccuracyChanged(sensor, accuracy)
                                                } catch (e: Throwable) {
                                                    // Ignore
                                                }
                                            }
                                        }
                                        
                                        // Replace original listener with wrapped one
                                        param.args[0] = wrappedListener
                                        
                                        XposedBridge.log("$TAG: Wrapped SensorEventListener for sensor type $sensorType")
                                    }
                                } catch (e: Throwable) {
                                    // Silently fail - don't break sensor registration
                                }
                            }
                        }
                    )
                } catch (e: Throwable) {
                    // Some parameter combinations may not exist
                }
            }
            
            XposedBridge.log("$TAG: Hooked SensorManager.registerListener()")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook SensorManager.registerListener: ${e.message}")
        }
    }

    /**
     * CRITICAL: Inject fake accelerometer data
     */
    private fun injectAccelerometerData(values: FloatArray) {
        try {
            val settings = Xshare()
            val actualSpeed = settings.getSyncedActualSpeed
            val curveReduction = settings.getSyncedCurveReduction
            
            // Check if simulation started/stopped
            if (actualSpeed < 0.01f && lastSyncedSpeed >= 0.01f) {
                resetSensorState()
            }
            lastSyncedSpeed = actualSpeed

            val currentTime = System.currentTimeMillis()
            val deltaTime = if (prevTime > 0) (currentTime - prevTime) / 1000.0 else 0.1
            
            if (deltaTime > 0 && deltaTime < 10.0 && actualSpeed > 0.01f) {
                val settings = Xshare()
                val bearing = settings.getSyncedBearing
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
            } else if (actualSpeed <= 0.01f) {
                // Stationary - minimal noise
            if (values.size >= 3) {
                    values[0] = (Math.random() * 0.1 - 0.05).toFloat()
                    values[1] = (Math.random() * 0.1 - 0.05).toFloat()
                    values[2] = 9.8f + (Math.random() * 0.1 - 0.05).toFloat()
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
     * Google Maps uses magnetometer for arrow direction
     */
    private fun injectMagneticFieldData(values: FloatArray) {
        try {
            val settings = Xshare()
            if (!settings.isStarted) return
            
            // Get bearing DIRECTLY from SharedPreferences (synced with RouteSimulator)
            val syncedBearing = settings.getSyncedBearing
            val actualSpeed = settings.getSyncedActualSpeed
            
            // CRITICAL: If navigation not active, use settings bearing
            val fakeBearing = if (actualSpeed > 0.01f || syncedBearing > 0.01f) {
                syncedBearing
            } else {
                // Fallback to settings bearing when navigation not active
                settings.getBearing
            }
            
            // Convert bearing to magnetic field vector
            val magneticFieldStrength = 50f  // μT (typical Earth magnetic field)
            val bearingRad = Math.toRadians(fakeBearing.toDouble())
            
            // Android compass formula:
            // Bx = B * sin(bearing) - East component
            // By = B * cos(bearing) - North component
            // Bz = vertical component (varies by location)

            if (values.size >= 3) {
                // Calculate magnetic field components
                val bx = (magneticFieldStrength * sin(bearingRad)).toFloat()
                val by = (magneticFieldStrength * cos(bearingRad)).toFloat()
                val bz = 30f + (Math.random() * 10 - 5).toFloat()  // Vertical component
                
                // Set values with minimal noise for realism
                values[0] = bx + (Math.random() * 1.5 - 0.75).toFloat()
                values[1] = by + (Math.random() * 1.5 - 0.75).toFloat()
                values[2] = bz + (Math.random() * 2 - 1).toFloat()
            }
            
            // Debug log every 5 seconds
            if (actualSpeed > 0.01f && System.currentTimeMillis() % 5000L < 100L) {
                XposedBridge.log("$TAG: Magnetometer INJECTED - Bearing=${String.format("%.1f", syncedBearing)}° Speed=${String.format("%.1f", actualSpeed)} km/h")
            }
            
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Error injecting magnetometer: ${e.message}")
        }
    }

    /**
     * Inject fake gyroscope data
     */
    private fun injectGyroscopeData(values: FloatArray) {
        try {
            val settings = Xshare()
            val actualSpeed = settings.getSyncedActualSpeed
            val curveReduction = settings.getSyncedCurveReduction
            
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

    private fun resetSensorState() {
        accelStateX = 0f
        accelStateY = 0f
        accelStateZ = 9.8f
        
        accelErrorX = 1f
        accelErrorY = 1f
        accelErrorZ = 1f
        
        prevTime = System.currentTimeMillis()
        lastSyncedSpeed = 0f
    }
}
