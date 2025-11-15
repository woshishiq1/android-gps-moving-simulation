package io.github.mwarevn.movingsimulation.xposed

import android.location.Location
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.mwarevn.movingsimulation.BuildConfig
import kotlin.math.*

/**
 * AdvancedRandomizer - Advanced variance injection for ML anomaly detection bypass
 *
 * CRITICAL IMPLEMENTATION FOR FULL FLAVOR:
 * This hook injects realistic variance in GPS location data timestamps and speed values
 * to bypass ML models that detect "too perfect movement" as spoofing.
 *
 * Detection Bypass:
 * - ML models detect fake GPS by analyzing movement patterns:
 *   * Perfect speed consistency (no natural acceleration/deceleration)
 *   * Perfectly spaced timestamps (unlike real GPS)
 *   * Zero jitter in reported values
 *   * Impossible acceleration curves (instant speed changes)
 * - Our advanced randomizer makes spoofed movement look "messy" like real GPS
 *
 * Scope: ONLY affects app-level hooks (scoped apps)
 * - Package filter: ignores "android" (system) and BuildConfig.APPLICATION_ID (own app)
 * - Does NOT affect actual route or final position
 *
 * Technical Details:
 * - Gaussian variance for timestamp (±50ms around RouteSimulator 300ms base)
 * - Speed variance with acceleration ramps (0-100% over 1-2 seconds)
 * - Position jitter uses Brownian motion for realistic walk
 * - Bearing (direction) changes smoothly, not instantly
 * - Accuracy variance simulates different GPS signal quality
 * - All variance synchronized with RouteSimulator update intervals
 */
object AdvancedRandomizer {

    private const val TAG = "AdvancedRandomizer"

    // Randomization parameters
    private const val TIMESTAMP_VARIANCE_MS = 50L      // ±50ms around base interval
    private const val SPEED_VARIANCE_PERCENT = 15       // ±15% speed variation
    private const val POSITION_JITTER_METERS = 2.5f    // ±2.5m realistic jitter
    private const val BEARING_SMOOTHING_FACTOR = 0.3f  // 0-1: higher = smoother

    // State tracking for realistic variance
    private var lastTimestamp = 0L
    private var lastSpeed = 0f
    private var lastBearing = 0f
    private var lastAccuracy = 10f

    // Brownian motion for position jitter
    private var brownianX = 0.0
    private var brownianY = 0.0
    private val brownianAlpha = 0.3  // Brownian motion smoothing

    // Speed acceleration ramp (smooth speed changes)
    private var targetSpeed = 0f
    private var accelerationPhase = 0f  // 0-1 for acceleration/deceleration ramp

    // Gaussian random for natural variance
    private val random = java.util.Random()

    fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Only hook at app level (scoped apps)
        // Skip system package and own app
        if (lpparam.packageName == "android" || lpparam.packageName == BuildConfig.APPLICATION_ID) {
            return
        }

        try {
            // Hook Location methods to inject variance
            hookLocationVariance(lpparam)

            XposedBridge.log("$TAG: Initialized for package ${lpparam.packageName}")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to initialize: ${e.message}")
        }
    }

    /**
     * Hook Location methods to inject advanced variance
     * This modifies the values returned by Location.getXxx() methods
     */
    private fun hookLocationVariance(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val locationClass = XposedHelpers.findClass(
                "android.location.Location",
                lpparam.classLoader
            )

            // Hook getTime() to add timestamp variance
            hookLocationGetter(
                locationClass,
                "getTime",
                lpparam
            ) { location ->
                val originalTime = XposedHelpers.callMethod(location, "getTime_original", Long::class.java) as? Long ?: System.currentTimeMillis()
                addTimestampVariance(originalTime)
            }

            // Hook getSpeed() to add speed variance
            hookLocationGetter(
                locationClass,
                "getSpeed",
                lpparam
            ) { location ->
                val originalSpeed = XposedHelpers.callMethod(location, "getSpeed_original", Float::class.java) as? Float ?: 0f
                addSpeedVariance(originalSpeed)
            }

            // Hook getAccuracy() to add accuracy variance
            hookLocationGetter(
                locationClass,
                "getAccuracy",
                lpparam
            ) { location ->
                val originalAccuracy = XposedHelpers.callMethod(location, "getAccuracy_original", Float::class.java) as? Float ?: 10f
                addAccuracyVariance(originalAccuracy)
            }

            // Hook getBearing() to add bearing smoothing
            hookLocationGetter(
                locationClass,
                "getBearing",
                lpparam
            ) { location ->
                val originalBearing = XposedHelpers.callMethod(location, "getBearing_original", Float::class.java) as? Float ?: 0f
                smoothBearing(originalBearing)
            }

            // Hook getLatitude() and getLongitude() to add position jitter
            hookLocationPositionJitter(locationClass, "getLatitude", lpparam) { location ->
                val originalLat = XposedHelpers.callMethod(location, "getLatitude_original", Double::class.java) as? Double ?: 0.0
                addPositionJitter(originalLat, true)
            }

            hookLocationPositionJitter(locationClass, "getLongitude", lpparam) { location ->
                val originalLng = XposedHelpers.callMethod(location, "getLongitude_original", Double::class.java) as? Double ?: 0.0
                addPositionJitter(originalLng, false)
            }

        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook Location methods: ${e.message}")
        }
    }

    /**
     * Generic method hooker for Location getters
     */
    private fun hookLocationGetter(
        locationClass: Class<*>,
        methodName: String,
        lpparam: XC_LoadPackage.LoadPackageParam,
        varianceFunction: (Any) -> Any
    ) {
        try {
            XposedHelpers.findAndHookMethod(
                locationClass,
                methodName,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val newValue = varianceFunction(param.thisObject)
                            param.result = newValue
                        } catch (e: Throwable) {
                            // Silently fail - use original value
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            // Ignore if method doesn't exist
        }
    }

    /**
     * Hook position methods separately to track state
     */
    private fun hookLocationPositionJitter(
        locationClass: Class<*>,
        methodName: String,
        lpparam: XC_LoadPackage.LoadPackageParam,
        varianceFunction: (Any) -> Any
    ) {
        try {
            XposedHelpers.findAndHookMethod(
                locationClass,
                methodName,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val newValue = varianceFunction(param.thisObject)
                            param.result = newValue
                        } catch (e: Throwable) {
                            // Silently fail
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            // Ignore
        }
    }

    /**
     * Add variance to timestamp to simulate GPS update intervals
     * Real GPS has variable update intervals (250-350ms)
     * This adds Gaussian variance around the base interval
     */
    private fun addTimestampVariance(originalTime: Long): Long {
        // Generate Gaussian variance (±50ms)
        val variance = (random.nextGaussian() * TIMESTAMP_VARIANCE_MS).toLong()

        val variedTime = originalTime + variance
        lastTimestamp = variedTime

        return variedTime
    }

    /**
     * Add realistic speed variance with acceleration ramps
     * Real movement has acceleration/deceleration, not instant speed changes
     * This creates smooth speed transitions
     */
    private fun addSpeedVariance(originalSpeed: Float): Float {
        // Update target speed with variance
        if (random.nextDouble() < 0.1) {  // 10% chance to change target
            targetSpeed = (originalSpeed * (0.85f + random.nextDouble() * 0.3)).toFloat()
            accelerationPhase = 0f
        }

        // Smooth transition from lastSpeed to targetSpeed
        accelerationPhase = minOf(1f, accelerationPhase + 0.05f)  // 20 steps for smooth ramp
        val smoothedSpeed = lastSpeed + (targetSpeed - lastSpeed) * accelerationPhase

        // Add small random jitter
        val jitter = (random.nextGaussian() * originalSpeed * (SPEED_VARIANCE_PERCENT / 100f)).toFloat()
        val variedSpeed = (smoothedSpeed + jitter).coerceAtLeast(0f)

        lastSpeed = variedSpeed
        return variedSpeed
    }

    /**
     * Add accuracy variance to simulate GPS signal quality changes
     * Real GPS accuracy varies based on signal strength
     * This simulates realistic fluctuations
     */
    private fun addAccuracyVariance(originalAccuracy: Float): Float {
        // Slowly vary accuracy over time
        val accuracyTarget = originalAccuracy * (0.8f + random.nextDouble() * 0.5).toFloat()
        lastAccuracy = lastAccuracy * 0.7f + accuracyTarget * 0.3f

        // Add small high-frequency jitter
        val jitter = (random.nextGaussian() * originalAccuracy * 0.1).toFloat()
        val variedAccuracy = (lastAccuracy + jitter).coerceAtLeast(1f)

        return variedAccuracy
    }

    /**
     * Smooth bearing changes to prevent instant direction changes
     * Real devices rotate gradually, not instantly
     */
    private fun smoothBearing(originalBearing: Float): Float {
        // Smooth transition from lastBearing to originalBearing
        val smoothed = lastBearing * (1 - BEARING_SMOOTHING_FACTOR) +
                      originalBearing * BEARING_SMOOTHING_FACTOR

        // Normalize to 0-360
        val normalized = if (smoothed < 0) smoothed + 360 else if (smoothed >= 360) smoothed - 360 else smoothed

        lastBearing = normalized
        return normalized
    }

    /**
     * Add Brownian motion jitter to position for realistic walk
     * This makes position appear to "wander" slightly like real GPS
     */
    private fun addPositionJitter(originalValue: Double, isLatitude: Boolean): Double {
        // Generate Brownian motion (random walk)
        val newBrownian = if (isLatitude) {
            brownianX = brownianX * (1 - brownianAlpha) +
                       (random.nextGaussian() * 0.000015).toFloat() // ~1m variance
            brownianX
        } else {
            brownianY = brownianY * (1 - brownianAlpha) +
                       (random.nextGaussian() * 0.000015).toFloat()
            brownianY
        }

        // Add absolute jitter
        val jitter = random.nextGaussian() * 0.000015  // ~1m jitter
        val variedValue = originalValue + newBrownian + jitter

        return variedValue
    }

    /**
     * Generate realistic variance pattern based on time
     * Creates patterns that mimic real GPS behavior
     */
    private fun getVariancePattern(): Float {
        // Subtle variation based on time
        val timePattern = sin(System.currentTimeMillis().toDouble() / 5000.0)
        return (timePattern * 0.5 + 0.5).toFloat()
    }
}
