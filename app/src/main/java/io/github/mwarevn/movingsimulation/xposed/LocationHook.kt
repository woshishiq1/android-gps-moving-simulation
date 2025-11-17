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
            
            // Get synchronized bearing from SpeedSyncManager (for route navigation)
            // CRITICAL FIX: Always use synced bearing when GPS is started
            val syncedBearing = SpeedSyncManager.getBearing()
            bearing = if (settings.isStarted) {
                // When GPS is active, ALWAYS use synced bearing from RouteSimulator
                // This ensures bearing matches actual movement direction perfectly
                syncedBearing
            } else {
                // Only when GPS is completely off, use user-set bearing
                settings.getBearing
            }
            
            // Get actual speed from RouteSimulator via SpeedSyncManager
            // This syncs with actual movement speed (including curve reduction)
            val syncedSpeed = SpeedSyncManager.getActualSpeed()
            speed = if (settings.isStarted && syncedSpeed >= 0f) {
                // When GPS is active, use actual simulation speed (can be 0 when stationary)
                SpeedSyncManager.speedKmhToMs(syncedSpeed)
            } else {
                // Only when GPS is off, use settings speed
                settings.getSpeed
            }

            // Debug logging - show what speed was set
            if (syncedSpeed > 0.01f && mLastUpdated % 3000L < 500L) {  // Log every ~3 seconds
                XposedBridge.log("LocationHook: Speed=${String.format("%.1f", syncedSpeed)} km/h (${String.format("%.2f", speed)} m/s), Reduction=${String.format("%.2f", SpeedSyncManager.getCurveReduction())}")
            }

        } catch (e: Exception) {
            // Silently fail - don't use context.packageName as it may crash early
            // Timber.tag("GPS Setter").e(e, "Failed to get XposedSettings")
        }
    }
    
    /**
     * CRITICAL HELPER: Set Location fields with proper internal flags
     * This ensures Google Maps displays speed/bearing correctly
     */
    private fun setLocationFieldsWithFlags(location: Location) {
        try {
            // Set hasSpeed and hasBearing flags using reflection
            val locationClass = Location::class.java
            val setFieldsMaskMethod = locationClass.getDeclaredMethod("setFieldsMask", Int::class.javaPrimitiveType)
            setFieldsMaskMethod.isAccessible = true
            
            // Location field masks (from Android source code)
            val HAS_LAT_LONG_BIT = 1
            val HAS_ALTITUDE_BIT = 2
            val HAS_SPEED_BIT = 4
            val HAS_BEARING_BIT = 8
            val HAS_ACCURACY_BIT = 16
            
            val fieldsMask = HAS_LAT_LONG_BIT or HAS_ALTITUDE_BIT or HAS_SPEED_BIT or 
                            HAS_BEARING_BIT or HAS_ACCURACY_BIT
            setFieldsMaskMethod.invoke(location, fieldsMask)
        } catch (e: Throwable) {
            // Flags setting failed - Location object might still work without explicit flags
            // on some Android versions
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
            // Note: updateLocation() is called in each hook method for real-time bearing
            // No need to pre-call here

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
                            // CRITICAL: Force update to get FRESH bearing and speed
                            updateLocation()
                            
                            val location = Location(LocationManager.GPS_PROVIDER)
                            // CRITICAL FIX: ALWAYS use fresh timestamps (not cached)
                            location.time = System.currentTimeMillis()
                            location.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                            
                            location.latitude = newlat
                            location.longitude = newlng
                            location.altitude = 0.0
                            
                            // CRITICAL: Set speed and bearing
                            location.speed = speed
                            location.bearing = bearing
                            
                            // Ensure accuracy is valid (5-20m is realistic for GPS)
                            location.accuracy = accuracy.coerceIn(5f, 20f)
                            location.speedAccuracyMetersPerSecond = 0.5F
                            location.bearingAccuracyDegrees = 10f
                            
                            // CRITICAL: Set hasSpeed/hasBearing flags for Google Maps
                            setLocationFieldsWithFlags(location)
                            
                            // Add GPS extras for better validity
                            val extras = android.os.Bundle()
                            extras.putInt("satellites", 12)
                            location.extras = extras
                            
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
                            // CRITICAL: Force update to get FRESH bearing and speed
                            updateLocation()
                            
                            lateinit var location: Location
                            if (param.args[0] == null) {
                                location = Location(LocationManager.GPS_PROVIDER)
                            } else {
                                val originLocation = param.args[0] as Location
                                location = Location(originLocation.provider)
                                location.verticalAccuracyMeters = originLocation.verticalAccuracyMeters
                            }
                            
                            // CRITICAL: ALWAYS use fresh timestamps (not cached from originLocation)
                            location.time = System.currentTimeMillis()
                            location.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()

                            location.latitude = newlat
                            location.longitude = newlng
                            location.altitude = 0.0
                            location.accuracy = accuracy.coerceIn(5f, 20f)
                            location.bearing = bearing
                            location.speed = speed
                            location.speedAccuracyMetersPerSecond = 0.5F
                            location.bearingAccuracyDegrees = 10f
                            
                            // CRITICAL: Set hasSpeed/hasBearing flags for Google Maps
                            setLocationFieldsWithFlags(location)
                            
                            // Add GPS extras for better validity
                            val extras = android.os.Bundle()
                            extras.putInt("satellites", 12)
                            location.extras = extras
                            
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
                                    // CRITICAL: Force update to get FRESH bearing and speed
                                    updateLocation()
                                    
                                    val location = Location(LocationManager.GPS_PROVIDER)
                                    // CRITICAL: ALWAYS use fresh timestamps
                                    location.time = System.currentTimeMillis()
                                    location.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                                    
                                    location.latitude = newlat
                                    location.longitude = newlng
                                    location.altitude = 0.0
                                    location.speed = speed
                                    location.bearing = bearing
                                    
                                    location.accuracy = accuracy.coerceIn(5f, 20f)
                                    location.speedAccuracyMetersPerSecond = 0.5F
                                    location.bearingAccuracyDegrees = 10f
                                    
                                    // CRITICAL: Set hasSpeed/hasBearing flags for Google Maps
                                    setLocationFieldsWithFlags(location)
                                    
                                    // Add GPS extras for better validity
                                    val extras = android.os.Bundle()
                                    extras.putInt("satellites", 12)
                                    location.extras = extras
                                    
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
                            // CRITICAL: Force update to get FRESH bearing and speed
                            updateLocation()
                            
                            lateinit var location: Location
                            if (param.args[0] == null) {
                                location = Location(LocationManager.GPS_PROVIDER)
                            } else {
                                val originLocation = param.args[0] as Location
                                location = Location(originLocation.provider)
                                location.verticalAccuracyMeters = originLocation.verticalAccuracyMeters
                            }
                            
                            // CRITICAL: ALWAYS use fresh timestamps (not cached from originLocation)
                            location.time = System.currentTimeMillis()
                            location.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()

                            location.latitude = newlat
                            location.longitude = newlng
                            location.altitude = 0.0
                            location.accuracy = accuracy.coerceIn(5f, 20f)
                            location.bearing = bearing
                            location.speed = speed
                            location.speedAccuracyMetersPerSecond = 0.5F
                            location.bearingAccuracyDegrees = 10f
                            
                            // CRITICAL: Set hasSpeed/hasBearing flags for Google Maps
                            setLocationFieldsWithFlags(location)
                            
                            // Add GPS extras for better validity
                            val extras = android.os.Bundle()
                            extras.putInt("satellites", 12)
                            location.extras = extras
                            
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
                            
                            // CRITICAL: ALWAYS update to get fresh bearing/speed (no interval check)
                            updateLocation()
                            
                            try {
                                val originLocation = param.args[0] as? Location ?: return
                                val location = Location(originLocation.provider)
                                
                                // CRITICAL: ALWAYS use fresh timestamps (not cached)
                                location.time = System.currentTimeMillis()
                                location.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                                
                                location.latitude = newlat
                                location.longitude = newlng
                                location.altitude = 0.0
                                location.accuracy = accuracy.coerceIn(5f, 20f)
                                location.bearing = bearing
                                location.bearingAccuracyDegrees = 10f
                                location.speed = speed
                                location.speedAccuracyMetersPerSecond = 0.5F
                                location.verticalAccuracyMeters = originLocation.verticalAccuracyMeters ?: 5f
                                
                                // CRITICAL: Set hasSpeed/hasBearing flags for Google Maps
                                setLocationFieldsWithFlags(location)
                                
                                // Add GPS extras for better validity
                                val extras = android.os.Bundle()
                                extras.putInt("satellites", 12)
                                location.extras = extras
                                
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
                            
                            // CRITICAL: ALWAYS update to get fresh bearing/speed (no interval check)
                            updateLocation()
                            
                            try {
                                val provider = param.args[0] as String
                                val location = Location(provider)
                                // CRITICAL: ALWAYS use fresh timestamps
                                location.time = System.currentTimeMillis()
                                location.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                                
                                location.latitude = newlat
                                location.longitude = newlng
                                location.altitude = 0.0
                                location.accuracy = accuracy.coerceIn(5f, 20f)
                                location.bearing = bearing
                                location.bearingAccuracyDegrees = 10f
                                location.speed = speed
                                location.speedAccuracyMetersPerSecond = 0.5F
                                
                                // CRITICAL: Set hasSpeed/hasBearing flags for Google Maps
                                setLocationFieldsWithFlags(location)
                                
                                // Add GPS extras for better validity
                                val extras = android.os.Bundle()
                                extras.putInt("satellites", 12)
                                location.extras = extras
                                
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

            // CRITICAL: Hook LocationManager.requestLocationUpdates() for continuous updates
            // This is THE MOST IMPORTANT hook for Google Maps and navigation apps
            try {
                val locationManagerClass = XposedHelpers.findClass(
                    "android.location.LocationManager",
                    lpparam.classLoader
                )
                
                // Hook requestLocationUpdates with LocationListener
                try {
                    XposedHelpers.findAndHookMethod(
                        locationManagerClass,
                        "requestLocationUpdates",
                        String::class.java,
                        Long::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType,
                        android.location.LocationListener::class.java,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    if (!settings.isStarted || ignorePkg.contains(lpparam.packageName)) return
                                    
                                    val listener = param.args[3] as? android.location.LocationListener
                                    if (listener != null) {
                                        // Wrap listener to inject fake locations continuously
                                        val wrappedListener = object : android.location.LocationListener {
                                            override fun onLocationChanged(location: Location) {
                                                try {
                                                    // CRITICAL: Update location data
                                                    updateLocation()
                                                    
                                                    // Create fake location
                                                    val fakeLocation = Location(location.provider)
                                                    fakeLocation.time = System.currentTimeMillis()
                                                    fakeLocation.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                                                    fakeLocation.latitude = newlat
                                                    fakeLocation.longitude = newlng
                                                    fakeLocation.altitude = 0.0
                                                    fakeLocation.accuracy = accuracy.coerceIn(5f, 20f)
                                                    fakeLocation.bearing = bearing
                                                    fakeLocation.bearingAccuracyDegrees = 10f
                                                    fakeLocation.speed = speed
                                                    fakeLocation.speedAccuracyMetersPerSecond = 0.5F
                                                    
                                                    // CRITICAL: Set flags
                                                    setLocationFieldsWithFlags(fakeLocation)
                                                    
                                                    // Add extras
                                                    val extras = android.os.Bundle()
                                                    extras.putInt("satellites", 12)
                                                    fakeLocation.extras = extras
                                                    
                                                    try {
                                                        HiddenApiBypass.invoke(
                                                            fakeLocation.javaClass, fakeLocation, "setIsFromMockProvider", false
                                                        )
                                                    } catch (e: Exception) { /* Ignore */ }
                                                    
                                                    // Call original listener with fake location
                                                    listener.onLocationChanged(fakeLocation)
                                                } catch (e: Throwable) {
                                                    // Fallback: call original
                                                    try {
                                                        listener.onLocationChanged(location)
                                                    } catch (e2: Throwable) { /* Ignore */ }
                                                }
                                            }
                                            
                                            override fun onStatusChanged(provider: String, status: Int, extras: android.os.Bundle) {
                                                try {
                                                    listener.onStatusChanged(provider, status, extras)
                                                } catch (e: Throwable) { /* Ignore */ }
                                            }
                                            
                                            override fun onProviderEnabled(provider: String) {
                                                try {
                                                    listener.onProviderEnabled(provider)
                                                } catch (e: Throwable) { /* Ignore */ }
                                            }
                                            
                                            override fun onProviderDisabled(provider: String) {
                                                try {
                                                    listener.onProviderDisabled(provider)
                                                } catch (e: Throwable) { /* Ignore */ }
                                            }
                                        }
                                        
                                        // Replace listener
                                        param.args[3] = wrappedListener
                                        
                                        XposedBridge.log("LocationHook: Wrapped LocationListener for continuous updates")
                                    }
                                } catch (e: Throwable) {
                                    // Silently fail
                                }
                            }
                        }
                    )
                } catch (e: Throwable) {
                    // Method signature may vary
                }
                
                // Also hook with LocationRequest (Android 8.0+)
                try {
                    XposedHelpers.findAndHookMethod(
                        locationManagerClass,
                        "requestLocationUpdates",
                        android.location.LocationRequest::class.java,
                        android.location.LocationListener::class.java,
                        android.os.Looper::class.java,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    if (!settings.isStarted || ignorePkg.contains(lpparam.packageName)) return
                                    
                                    val listener = param.args[1] as? android.location.LocationListener
                                    if (listener != null) {
                                        // Same wrapping logic as above
                                        val wrappedListener = object : android.location.LocationListener {
                                            override fun onLocationChanged(location: Location) {
                                                try {
                                                    updateLocation()
                                                    
                                                    val fakeLocation = Location(location.provider)
                                                    fakeLocation.time = System.currentTimeMillis()
                                                    fakeLocation.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                                                    fakeLocation.latitude = newlat
                                                    fakeLocation.longitude = newlng
                                                    fakeLocation.altitude = 0.0
                                                    fakeLocation.accuracy = accuracy.coerceIn(5f, 20f)
                                                    fakeLocation.bearing = bearing
                                                    fakeLocation.bearingAccuracyDegrees = 10f
                                                    fakeLocation.speed = speed
                                                    fakeLocation.speedAccuracyMetersPerSecond = 0.5F
                                                    
                                                    setLocationFieldsWithFlags(fakeLocation)
                                                    
                                                    val extras = android.os.Bundle()
                                                    extras.putInt("satellites", 12)
                                                    fakeLocation.extras = extras
                                                    
                                                    try {
                                                        HiddenApiBypass.invoke(
                                                            fakeLocation.javaClass, fakeLocation, "setIsFromMockProvider", false
                                                        )
                                                    } catch (e: Exception) { /* Ignore */ }
                                                    
                                                    listener.onLocationChanged(fakeLocation)
                                                } catch (e: Throwable) {
                                                    try {
                                                        listener.onLocationChanged(location)
                                                    } catch (e2: Throwable) { /* Ignore */ }
                                                }
                                            }
                                            
                                            override fun onStatusChanged(provider: String, status: Int, extras: android.os.Bundle) {
                                                try {
                                                    listener.onStatusChanged(provider, status, extras)
                                                } catch (e: Throwable) { /* Ignore */ }
                                            }
                                            
                                            override fun onProviderEnabled(provider: String) {
                                                try {
                                                    listener.onProviderEnabled(provider)
                                                } catch (e: Throwable) { /* Ignore */ }
                                            }
                                            
                                            override fun onProviderDisabled(provider: String) {
                                                try {
                                                    listener.onProviderDisabled(provider)
                                                } catch (e: Throwable) { /* Ignore */ }
                                            }
                                        }
                                        
                                        param.args[1] = wrappedListener
                                        
                                        XposedBridge.log("LocationHook: Wrapped LocationListener (LocationRequest)")
                                    }
                                } catch (e: Throwable) {
                                    // Silently fail
                                }
                            }
                        }
                    )
                } catch (e: Throwable) {
                    // May not exist on older Android versions
                }
                
            } catch (e: Throwable) {
                XposedBridge.log("LocationHook: Failed to hook requestLocationUpdates: ${e.message}")
            }

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
