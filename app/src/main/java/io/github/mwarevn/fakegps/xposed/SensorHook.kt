package io.github.mwarevn.fakegps.xposed

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.mwarevn.fakegps.BuildConfig
import java.lang.reflect.Field
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * SensorHook - Fake sensor data synchronized with GPS movement state.
 *
 * When stationary: sensors report idle/gravity-only values with small noise.
 * When navigating: sensors reflect movement (accelerometer shows motion,
 * gyroscope shows turning, magnetometer aligns with bearing, step counter increments).
 */
object SensorHook {

    private val rand = Random()
    private val settings = Xshare()
    
    // Step counter state
    private var baseStepCount = -1f
    private var simulatedSteps = 0f
    private var lastStepTime = 0L

    // Bearing tracking for gyroscope
    private var lastBearing = 0f
    private var lastBearingTime = 0L

    // Cache SensorEvent.values field for reflection
    private var valuesField: Field? = null

    @JvmStatic
    fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == BuildConfig.APPLICATION_ID) return
        if (lpparam.packageName == "android") return

        try {
            hookSensorManager(lpparam)
        } catch (e: Throwable) {
            XposedBridge.log("GPS Setter SensorHook: Failed to init: ${e.message}")
        }
    }

    private fun hookSensorManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        val smClass = XposedHelpers.findClassIfExists(
            "android.hardware.SensorManager", lpparam.classLoader
        ) ?: return

        // Hook registerListener to wrap the SensorEventListener with our proxy
        XposedBridge.hookAllMethods(smClass, "registerListener", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                settings.reload()
                if (!settings.isStarted || !settings.isSensorSpoofEnabled) return

                for (i in param.args.indices) {
                    val arg = param.args[i] ?: continue
                    if (arg is SensorEventListener) {
                        // Don't wrap our own app's listeners
                        if (arg.javaClass.name.contains("io.github.mwarevn")) return
                        // Don't double-wrap
                        if (arg is SensorProxyListener) return

                        param.args[i] = SensorProxyListener(arg)
                        break
                    }
                }
            }
        })
    }

    /**
     * Proxy listener that intercepts real sensor events and modifies values
     * to match the simulated GPS movement state.
     */
    private class SensorProxyListener(
        private val original: SensorEventListener
    ) : SensorEventListener {

        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null) {
                original.onSensorChanged(event)
                return
            }

            try {
                // Re-read settings periodically
                settings.reload()
                if (!settings.isStarted || !settings.isSensorSpoofEnabled) {
                    original.onSensorChanged(event)
                    return
                }

                // Read speed and bearing from XSharedPreferences (cross-process safe)
                val speedMs = settings.getSpeed // speed stored in m/s
                val speedKmh = speedMs * 3.6f
                val bearing = settings.getBearing // degrees
                val isMoving = speedKmh > 0.5f

                when (event.sensor?.type) {
                    Sensor.TYPE_ACCELEROMETER -> fakeSensorValues(event, generateAccelerometer(speedKmh, isMoving))
                    Sensor.TYPE_LINEAR_ACCELERATION -> fakeSensorValues(event, generateLinearAcceleration(speedKmh, isMoving))
                    Sensor.TYPE_GRAVITY -> fakeSensorValues(event, floatArrayOf(0f, 0f, 9.81f))
                    Sensor.TYPE_GYROSCOPE -> fakeSensorValues(event, generateGyroscope(bearing, isMoving))
                    Sensor.TYPE_MAGNETIC_FIELD -> fakeSensorValues(event, generateMagnetometer(bearing))
                    Sensor.TYPE_STEP_COUNTER -> fakeSensorValues(event, generateStepCounter(event, speedKmh, isMoving))
                    Sensor.TYPE_STEP_DETECTOR -> {
                        if (isMoving && shouldTriggerStep(speedKmh)) {
                            fakeSensorValues(event, floatArrayOf(1.0f))
                        } else if (!isMoving) {
                            // Don't deliver step events when stationary
                            return
                        }
                    }
                }
            } catch (e: Throwable) {
                // Fallback: deliver original event
            }

            original.onSensorChanged(event)
        }

        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {
            original.onAccuracyChanged(sensor, accuracy)
        }
    }

    /**
     * Modify SensorEvent.values using reflection (the field is final).
     */
    private fun fakeSensorValues(event: SensorEvent, values: FloatArray) {
        try {
            if (valuesField == null) {
                valuesField = SensorEvent::class.java.getDeclaredField("values")
                valuesField?.isAccessible = true
            }
            val currentValues = event.values
            for (i in values.indices) {
                if (i < currentValues.size) {
                    currentValues[i] = values[i]
                }
            }
        } catch (e: Throwable) {
            // Direct write fallback
            for (i in values.indices) {
                if (i < event.values.size) {
                    event.values[i] = values[i]
                }
            }
        }
    }

    // ============================================================
    // Sensor value generators
    // ============================================================

    private fun generateAccelerometer(speedKmh: Float, isMoving: Boolean): FloatArray {
        return if (isMoving) {
            // Moving: slight forward acceleration + gravity + vibration noise
            val speedMs = speedKmh / 3.6f
            val forwardAccel = (speedMs * 0.02f).coerceIn(-0.5f, 0.5f)
            floatArrayOf(
                forwardAccel + noise(0.08f),     // x - lateral
                noise(0.15f),                     // y - forward vibration
                9.81f + noise(0.12f)              // z - gravity + bounce
            )
        } else {
            // Stationary: gravity only with tiny noise
            floatArrayOf(
                noise(0.01f),
                noise(0.01f),
                9.81f + noise(0.005f)
            )
        }
    }

    private fun generateLinearAcceleration(speedKmh: Float, isMoving: Boolean): FloatArray {
        return if (isMoving) {
            val speedMs = speedKmh / 3.6f
            floatArrayOf(
                noise(0.05f),
                speedMs * 0.01f + noise(0.03f),
                noise(0.02f)
            )
        } else {
            floatArrayOf(noise(0.005f), noise(0.005f), noise(0.005f))
        }
    }

    private fun generateGyroscope(bearing: Float, isMoving: Boolean): FloatArray {
        val now = System.currentTimeMillis()
        if (isMoving && lastBearingTime > 0) {
            val dt = (now - lastBearingTime) / 1000f
            if (dt > 0.01f && dt < 5f) {
                var dBearing = bearing - lastBearing
                // Normalize to [-180, 180]
                while (dBearing > 180) dBearing -= 360
                while (dBearing < -180) dBearing += 360
                
                val angularVelocity = Math.toRadians(dBearing.toDouble()).toFloat() / dt
                lastBearing = bearing
                lastBearingTime = now
                
                return floatArrayOf(
                    noise(0.005f),               // x - pitch
                    noise(0.005f),               // y - roll
                    angularVelocity + noise(0.01f) // z - yaw (turning)
                )
            }
        }
        
        lastBearing = bearing
        lastBearingTime = now
        
        // Stationary or first reading
        return floatArrayOf(noise(0.002f), noise(0.002f), noise(0.002f))
    }

    private fun generateMagnetometer(bearing: Float): FloatArray {
        // Simulate Earth's magnetic field (~25-65 µT) rotated by bearing
        val bearingRad = Math.toRadians(bearing.toDouble())
        val horizontalIntensity = 25f // µT approximate
        val verticalIntensity = -40f  // µT (pointing down in northern hemisphere)
        
        return floatArrayOf(
            (horizontalIntensity * sin(bearingRad)).toFloat() + noise(0.5f),  // x
            (horizontalIntensity * cos(bearingRad)).toFloat() + noise(0.5f),  // y
            verticalIntensity + noise(0.3f)                                     // z
        )
    }

    private fun generateStepCounter(event: SensorEvent, speedKmh: Float, isMoving: Boolean): FloatArray {
        if (baseStepCount < 0 && event.values.isNotEmpty()) {
            baseStepCount = event.values[0]
            simulatedSteps = 0f
        }
        
        if (isMoving) {
            val now = System.currentTimeMillis()
            if (lastStepTime > 0) {
                val dt = (now - lastStepTime) / 1000f
                // Walking: ~1.4 steps/sec at 5 km/h, running: ~3 steps/sec at 12 km/h
                val stepsPerSec = when {
                    speedKmh <= 6f -> 1.4f    // walking
                    speedKmh <= 12f -> 2.5f   // jogging
                    else -> 0f                 // driving - no steps
                }
                simulatedSteps += stepsPerSec * dt
            }
            lastStepTime = now
        }
        
        return floatArrayOf(baseStepCount + simulatedSteps)
    }

    private fun shouldTriggerStep(speedKmh: Float): Boolean {
        val now = System.currentTimeMillis()
        val interval = when {
            speedKmh <= 6f -> 714L    // ~1.4 steps/sec
            speedKmh <= 12f -> 400L   // ~2.5 steps/sec
            else -> return false       // driving
        }
        
        if (now - lastStepTime >= interval) {
            lastStepTime = now
            return true
        }
        return false
    }

    /**
     * Generate Gaussian noise with given standard deviation.
     */
    private fun noise(stdDev: Float): Float {
        return (rand.nextGaussian() * stdDev).toFloat()
    }
}
