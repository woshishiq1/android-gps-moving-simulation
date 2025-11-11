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

    private val context by lazy { AndroidAppHelper.currentApplication() as Context }

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
            speed = settings.getSpeed

        } catch (e: Exception) {
            Timber.tag("GPS Setter")
                .e(e, "Failed to get XposedSettings for %s", context.packageName)
        }
    }

    // thêm helper để thêm package vào ignore list (an toàn)
    @JvmStatic
    fun addIgnoredPackage(pkg: String) {
        if (!ignorePkg.contains(pkg)) ignorePkg.add(pkg)
    }

    @SuppressLint("NewApi")
    fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {

        if (lpparam.packageName == "android") { XposedBridge.log("Hooking system server")
        if (settings.isStarted && (settings.isHookedSystem && !ignorePkg.contains(lpparam.packageName))) {
            // Optimize: Increase system hook interval to reduce overhead
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
        }
        } else { // application hook

            val LocationClass = XposedHelpers.findClass(
                "android.location.Location",
                lpparam.classLoader
            )
            // Optimize: Increase interval to 200ms to reduce CPU overhead
            // 50ms was causing too frequent updates and battery drain
            val interval = 200L // Balanced between smoothness and performance

            for (method in LocationClass.declaredMethods) {
                if (method.name == "getLatitude") {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (System.currentTimeMillis() - mLastUpdated > interval) {
                                    updateLocation()
                                }
                                if (settings.isStarted && !ignorePkg.contains(lpparam.packageName)) {
                                    param.result = newlat
                                }
                            }
                        }
                    )
                } else if (method.name == "getLongitude") {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (System.currentTimeMillis() - mLastUpdated > interval) {
                                    updateLocation()
                                }
                                if (settings.isStarted && !ignorePkg.contains(lpparam.packageName)) {
                                    param.result = newlng
                                }
                            }
                        }
                    )
                } else if (method.name == "getAccuracy") {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (System.currentTimeMillis() - mLastUpdated > interval) {
                                    updateLocation()
                                }
                                if (settings.isStarted && !ignorePkg.contains(lpparam.packageName)) {
                                    param.result = accuracy
                                }
                            }
                        }
                    )
                } else if (method.name == "getBearing") {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (System.currentTimeMillis() - mLastUpdated > interval) {
                                    updateLocation()
                                }
                                if (settings.isStarted && !ignorePkg.contains(lpparam.packageName)) {
                                    param.result = bearing
                                }
                            }
                        }
                    )
                } else if (method.name == "getSpeed") {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (System.currentTimeMillis() - mLastUpdated > interval) {
                                    updateLocation()
                                }
                                if (settings.isStarted && !ignorePkg.contains(lpparam.packageName)) {
                                    param.result = speed
                                }
                            }
                        }
                    )
                } else if (method.name == "hasBearing") {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (settings.isStarted && !ignorePkg.contains(lpparam.packageName)) {
                                    param.result = true // Always return true when GPS is set
                                }
                            }
                        }
                    )
                } else if (method.name == "hasSpeed") {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (settings.isStarted && !ignorePkg.contains(lpparam.packageName)) {
                                    param.result = true // Always return true when GPS is set
                                }
                            }
                        }
                    )
                } else if (method.name == "getTime") {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (settings.isStarted && !ignorePkg.contains(lpparam.packageName)) {
                                    // Return current time to indicate fresh GPS data
                                    param.result = System.currentTimeMillis()
                                }
                            }
                        }
                    )
                }
            }

            XposedHelpers.findAndHookMethod(
                LocationClass,
                "set",
                Location::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {

                        if (System.currentTimeMillis() - mLastUpdated > interval) {
                            updateLocation()
                        }
                        if (settings.isStarted && !ignorePkg.contains(lpparam.packageName)) {
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
                            // Optimize: Disable logging in production for better performance
                            // XposedBridge.log("GS(app): lat: ${location.latitude}, lon: ${location.longitude}, bearing: ${location.bearing}, speed: ${location.speed}")
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
                }
            )

            XposedHelpers.findAndHookMethod(
                "android.location.LocationManager",
                lpparam.classLoader,
                "getLastKnownLocation",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (System.currentTimeMillis() - mLastUpdated > interval) {
                            updateLocation()
                        }
                        if (settings.isStarted && !ignorePkg.contains(lpparam.packageName)) {
                            val provider = param.args[0] as String
                            val location = Location(provider)
                            location.time = System.currentTimeMillis() - 300
                            location.latitude = newlat
                            location.longitude = newlng
                            location.altitude = 0.0
                            location.speed = speed
                            location.bearing = bearing
                            location.accuracy = accuracy
                            location.speedAccuracyMetersPerSecond = 0F
                            // Optimize: Disable logging to reduce overhead
                            // XposedBridge.log("GS(getLastKnown): lat: ${location.latitude}, lon: ${location.longitude}, bearing: ${location.bearing}, speed: ${location.speed}")
                            try {
                                HiddenApiBypass.invoke(
                                    location.javaClass, location, "setIsFromMockProvider", false
                                )
                            } catch (e: Exception) {
                                XposedBridge.log("LocationHook: unable to set mock $e")
                            }
                            param.result = location
                        }
                    }
                }
            )
        }
    }
}
