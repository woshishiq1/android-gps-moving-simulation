package io.github.mwarevn.movingsimulation.xposed

// https://github.com/rovo89/XposedBridge/wiki/Helpers

import android.annotation.SuppressLint
import android.app.AndroidAppHelper
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.mwarevn.movingsimulation.BuildConfig
import io.github.mwarevn.movingsimulation.utils.SpeedSyncManager
import org.lsposed.hiddenapibypass.HiddenApiBypass
import timber.log.Timber
import java.util.*
import kotlin.math.cos

object LocationHook {

    var newlat: Double = 45.0000
    var newlng: Double = 0.0000
    var bearing: Float = 0F
    var speed: Float = 0F
    private const val pi = 3.14159265359
    private var accuracy: Float = 0.0f
    private val rand: Random = Random()
    private const val earth = 6378137.0
    private val settings = Xshare()
    private var mLastUpdated: Long = 0

    @JvmStatic
    private val ignorePkg = arrayListOf("com.android.location.fused", BuildConfig.APPLICATION_ID)

    // Safe context access - may be null during early app initialization
    private val context: Context? by lazy { 
        try {
            AndroidAppHelper.currentApplication() as? Context
        } catch (e: Throwable) {
            null
        }
    }

    private fun updateLocation() {
        try {
            mLastUpdated = System.currentTimeMillis()
            val x = (rand.nextInt(50) - 15).toDouble()
            val y = (rand.nextInt(50) - 15).toDouble()
            val dlat = x / earth
            val dlng = y / (earth * cos(pi * settings.getLat / 180.0))
            newlat =
                if (settings.isRandomPosition) settings.getLat + (dlat * 180.0 / pi) else settings.getLat
            newlng =
                if (settings.isRandomPosition) settings.getLng + (dlng * 180.0 / pi) else settings.getLng
            accuracy = settings.accuracy!!.toFloat()
            bearing = settings.getBearing
            
            // Get actual speed from RouteSimulator via SpeedSyncManager
            // This syncs with actual movement speed (including curve reduction)
            val syncedSpeed = SpeedSyncManager.getActualSpeed()
            speed = if (syncedSpeed > 0.01f) {
                // If RouteSimulator is running, use actual simulation speed
                SpeedSyncManager.speedKmhToMs(syncedSpeed)
            } else {
                // Fallback to settings speed when simulation is not active
                settings.getSpeed
            }

        } catch (e: Exception) {
            // Silently fail - don't use context.packageName as it may crash early
            // Timber.tag("GPS Setter").e(e, "Failed to get XposedSettings")
        }
    }

    // thêm helper để thêm package vào ignore list (an toàn)
    @JvmStatic
    fun addIgnoredPackage(pkg: String) {
        if (!ignorePkg.contains(pkg)) ignorePkg.add(pkg)
    }

    @SuppressLint("NewApi")
    fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {

        // Hook system server ONLY if explicitly enabled
        // This is DANGEROUS and should only be used when necessary
        if (lpparam.packageName == "android") {
            if (!settings.isStarted || !settings.isHookedSystem) {
                // Don't hook system unless explicitly enabled in settings
                XposedBridge.log("GPS Setter: Skipping system hook (disabled or not started)")
                return
            }

            XposedBridge.log("GPS Setter: Hooking system server (WARNING: This can be risky!)")
            hookSystemServer(lpparam)
            return
        }

        // For all other apps: Hook at application level (SAFE)
        // This works for most apps and doesn't risk bootloop
        hookApplicationLevel(lpparam)
    }

    /**
     * Hook system server - ONLY when explicitly enabled
     * This is RISKY and can cause bootloop if not careful
     */
    @SuppressLint("NewApi")
    private fun hookSystemServer(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            if (System.currentTimeMillis() - mLastUpdated > 200) {
                updateLocation()
            }

            if (Build.VERSION.SDK_INT < 34) {

                val LocationManagerServiceClass = XposedHelpers.findClass(
                    "com.android.server.LocationManagerService",
                    lpparam.classLoader
                )

                XposedHelpers.findAndHookMethod(
                    LocationManagerServiceClass, "getLastLocation",
                    LocationRequest::class.java, String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val location = Location(LocationManager.GPS_PROVIDER)
                            location.time = System.currentTimeMillis() - 300
                            location.latitude = newlat
                            location.longitude = newlng
                            location.altitude = 0.0
                            location.speed = speed
                            location.bearing = bearing
                            location.accuracy = accuracy
                            location.speedAccuracyMetersPerSecond = 0F
                            param.result = location
                        }
                    }
                )

                for (method in LocationManagerServiceClass.declaredMethods) {
                    if (method.returnType == Boolean::class.java) {
                        if (method.name == "addGnssBatchingCallback" ||
                            method.name == "addGnssMeasurementsListener" ||
                            method.name == "addGnssNavigationMessageListener"
                        ) {
                            XposedBridge.hookMethod(
                                method,
                                object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        param.result = false
                                    }
                                }
                            )
                        }
                    }
                }

                XposedHelpers.findAndHookMethod(
                    "com.android.server.LocationManagerService.Receiver", // com.android.server.LocationManagerService\$Receiver
                    lpparam.classLoader,
                    "callLocationChangedLocked",
                    Location::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            lateinit var location: Location
                            lateinit var originLocation: Location
                            if (param.args[0] == null) {
                                location = Location(LocationManager.GPS_PROVIDER)
                                location.time = System.currentTimeMillis() - 300
                            } else {
                                originLocation = param.args[0] as Location
                                location = Location(originLocation.provider)
                                location.time = originLocation.time
                                location.accuracy = accuracy
                                location.bearing = bearing
                                location.bearingAccuracyDegrees = originLocation.bearingAccuracyDegrees
                                location.elapsedRealtimeNanos = originLocation.elapsedRealtimeNanos
                                location.verticalAccuracyMeters = originLocation.verticalAccuracyMeters
                            }

                            location.latitude = newlat
                            location.longitude = newlng
                            location.altitude = 0.0
                            location.speed = speed
                            location.speedAccuracyMetersPerSecond = 0F
                            XposedBridge.log("GS: lat: ${location.latitude}, lon: ${location.longitude}, bearing: ${location.bearing}, speed: ${location.speed}")
                            try {
                                HiddenApiBypass.invoke(
                                    location.javaClass, location, "setIsFromMockProvider", false
                                )
                            } catch (e: Exception) {
                                XposedBridge.log("LocationHook: unable to set mock $e")
                            }
                            param.args[0] = location
                        }
                    }
                )
            } else {

                val LocationManagerServiceClass = XposedHelpers.findClass(
                    "com.android.server.location.LocationManagerService",
                    lpparam.classLoader
                )
                for (method in LocationManagerServiceClass.declaredMethods) {
                    if (method.name == "getLastLocation" && method.returnType == Location::class.java) {
                        // params: String::class.java, LastLocationRequest::class.java, String::class.java, String::class.java
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val location = Location(LocationManager.GPS_PROVIDER)
                                    location.time = System.currentTimeMillis() - 300
                                    location.latitude = newlat
                                    location.longitude = newlng
                                    location.altitude = 0.0
                                    location.speed = speed
                                    location.bearing = bearing
                                    location.accuracy = accuracy
                                    location.speedAccuracyMetersPerSecond = 0F
                                    param.result = location
                                }
                            }
                        )
                    } else if (method.returnType == Void::class.java) {
                        if (method.name == "startGnssBatch" ||
                            method.name == "addGnssAntennaInfoListener" ||
                            method.name == "addGnssMeasurementsListener" ||
                            method.name == "addGnssNavigationMessageListener"
                        ) {
                            XposedBridge.hookMethod(
                                method,
                                object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        param.result = null
                                    }
                                }
                            )
                        }
                    }
                }
                XposedHelpers.findAndHookMethod(
                    LocationManagerServiceClass,
                    "injectLocation",
                    Location::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            lateinit var location: Location
                            lateinit var originLocation: Location
                            if (param.args[0] == null) {
                                location = Location(LocationManager.GPS_PROVIDER)
                                location.time = System.currentTimeMillis() - 300
                            } else {
                                originLocation = param.args[0] as Location
                                location = Location(originLocation.provider)
                                location.time = originLocation.time
                                location.accuracy = accuracy
                                location.bearing = bearing
                                location.bearingAccuracyDegrees = originLocation.bearingAccuracyDegrees
                                location.elapsedRealtimeNanos = originLocation.elapsedRealtimeNanos
                                location.verticalAccuracyMeters = originLocation.verticalAccuracyMeters
                            }

                            location.latitude = newlat
                            location.longitude = newlng
                            location.altitude = 0.0
                            location.speed = speed
                            location.speedAccuracyMetersPerSecond = 0F
                            // Optimize: Remove excessive logging from Android 14+ hook
                            // XposedBridge.log("GS: lat: ${location.latitude}, lon: ${location.longitude}, bearing: ${location.bearing}, speed: ${location.speed}")
                            try {
                                HiddenApiBypass.invoke(
                                    location.javaClass, location, "setIsFromMockProvider", false
                                )
                            } catch (e: Exception) {
                                XposedBridge.log("LocationHook: unable to set mock $e")
                            }
                            param.args[0] = location
                        }
                    }
                )
            }
        } catch (e: Throwable) {
            XposedBridge.log("GPS Setter: System hook failed: ${e.message}")
        }
    }

    /**
     * Hook application level - SAFE approach
     * This hooks Location API at app level without touching system
     * OPTIMIZED: Only hook essential methods to prevent app freeze
     */
    private fun hookApplicationLevel(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Skip our own app and fused location provider
            if (ignorePkg.contains(lpparam.packageName)) {
                return
            }
            
            // Check if location spoofing is enabled
            if (!settings.isStarted) {
                return
            }

            // Optimize: Longer interval to reduce overhead
            val interval = 300L

            // Hook only essential Location methods
            val LocationClass = XposedHelpers.findClass(
                "android.location.Location",
                lpparam.classLoader
            )

            // Core location methods - MUST hook
            hookLocationMethod(LocationClass, "getLatitude", interval, lpparam) { newlat }
            hookLocationMethod(LocationClass, "getLongitude", interval, lpparam) { newlng }
            
            // Optional methods - only if needed
            try {
                hookLocationMethod(LocationClass, "getAccuracy", interval, lpparam) { accuracy }
            } catch (e: Throwable) { /* Ignore if method doesn't exist */ }

            // Hook Location.set() to modify location objects
            try {
                XposedHelpers.findAndHookMethod(
                    LocationClass,
                    "set",
                    Location::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!settings.isStarted || ignorePkg.contains(lpparam.packageName)) return
                            
                            if (System.currentTimeMillis() - mLastUpdated > interval) {
                                updateLocation()
                            }
                            
                            try {
                                val originLocation = param.args[0] as? Location ?: return
                                val location = Location(originLocation.provider)
                                location.time = originLocation.time
                                location.latitude = newlat
                                location.longitude = newlng
                                location.altitude = 0.0
                                location.accuracy = accuracy
                                location.bearing = bearing
                                location.speed = speed
                                location.elapsedRealtimeNanos = originLocation.elapsedRealtimeNanos
                                
                                try {
                                    HiddenApiBypass.invoke(
                                        location.javaClass, location, "setIsFromMockProvider", false
                                    )
                                } catch (e: Exception) { /* Ignore */ }
                                
                                param.args[0] = location
                            } catch (e: Throwable) { /* Ignore */ }
                        }
                    }
                )
            } catch (e: Throwable) { /* Ignore */ }

            // Hook LocationManager.getLastKnownLocation()
            try {
                XposedHelpers.findAndHookMethod(
                    "android.location.LocationManager",
                    lpparam.classLoader,
                    "getLastKnownLocation",
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!settings.isStarted || ignorePkg.contains(lpparam.packageName)) return
                            
                            if (System.currentTimeMillis() - mLastUpdated > interval) {
                                updateLocation()
                            }
                            
                            try {
                                val provider = param.args[0] as String
                                val location = Location(provider)
                                location.time = System.currentTimeMillis()
                                location.latitude = newlat
                                location.longitude = newlng
                                location.altitude = 0.0
                                location.accuracy = accuracy
                                location.bearing = bearing
                                location.speed = speed
                                
                                try {
                                    HiddenApiBypass.invoke(
                                        location.javaClass, location, "setIsFromMockProvider", false
                                    )
                                } catch (e: Exception) { /* Ignore */ }
                                
                                param.result = location
                            } catch (e: Throwable) { /* Ignore */ }
                        }
                    }
                )
            } catch (e: Throwable) { /* Ignore */ }

            // Hook Google Play Services - only if available
            try {
                hookFusedLocationProvider(lpparam)
            } catch (e: Throwable) { /* Not all apps have Google Play Services */ }
            
        } catch (e: Throwable) {
            // Silently fail - don't block app
        }
    }
    
    /**
     * Helper to hook location getter methods
     */
    private fun hookLocationMethod(
        locationClass: Class<*>,
        methodName: String,
        interval: Long,
        lpparam: XC_LoadPackage.LoadPackageParam,
        getValue: () -> Any
    ) {
        try {
            for (method in locationClass.declaredMethods) {
                if (method.name == methodName) {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!settings.isStarted || ignorePkg.contains(lpparam.packageName)) return
                                
                                if (System.currentTimeMillis() - mLastUpdated > interval) {
                                    updateLocation()
                                }
                                param.result = getValue()
                            }
                        }
                    )
                }
            }
        } catch (e: Throwable) {
            // Ignore if method doesn't exist
        }
    }

    /**
     * Hook Google Play Services FusedLocationProvider
     * Many modern apps (especially banking apps) use this instead of LocationManager
     * SIMPLIFIED: Only hook essential methods to prevent app freeze
     */
    private fun hookFusedLocationProvider(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Only hook if settings are started
            if (!settings.isStarted) return
            
            // Try to find FusedLocationProviderClient
            val fusedLocationClass = XposedHelpers.findClassIfExists(
                "com.google.android.gms.location.FusedLocationProviderClient",
                lpparam.classLoader
            ) ?: return

            // Hook getLastLocation() - most common method
            try {
                XposedHelpers.findAndHookMethod(
                    fusedLocationClass,
                    "getLastLocation",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!settings.isStarted) return
                            // Don't hook Task result - it's too complex and can cause freezes
                            // Let the app get real location from Task, we'll modify it via Location hooks
                        }
                    }
                )
            } catch (e: Throwable) { /* Ignore */ }

            // Don't hook requestLocationUpdates - too complex and causes freezes
            // Our Location.getLatitude/getLongitude hooks will handle it
            
        } catch (e: Throwable) {
            // FusedLocationProvider not available - that's fine
        }
    }

    /**
     * REMOVED: hookTaskResult and hookLocationCallback
     * These methods were causing app freezes by hooking too deep into Google Play Services
     * Our simpler Location hooks are sufficient
     */
}
