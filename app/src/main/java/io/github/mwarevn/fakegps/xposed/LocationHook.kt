package io.github.mwarevn.fakegps.xposed

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.mwarevn.fakegps.BuildConfig
import io.github.mwarevn.fakegps.utils.SpeedSyncManager
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.*
import kotlin.math.cos

object LocationHook {

    private var newlat: Double = 21.0285
    private var newlng: Double = 105.8542
    private var bearing: Float = 0F
    private var speed: Float = 0F
    private var accuracy: Float = 10f
    private var isStartedCache: Boolean = false
    
    private val rand: Random = Random()
    private const val earth = 6378137.0
    private const val pi = 3.14159265359
    private val settings = Xshare()
    private var mLastUpdated: Long = 0

    @JvmStatic
    private val ignorePkg = arrayListOf(BuildConfig.APPLICATION_ID, "com.android.location.fused")

    private fun updateLocation(force: Boolean = false) {
        try {
            val now = System.currentTimeMillis()
            val interval = if (settings.isRandomPosition) 400 else 1500
            
            if (force || now - mLastUpdated > interval) {
                settings.reload()
                mLastUpdated = now
                isStartedCache = settings.isStarted
                
                if (isStartedCache) {
                    val lat = settings.getLat
                    val lng = settings.getLng
                    
                    if (settings.isRandomPosition) {
                        val x = (rand.nextInt(12) - 6).toDouble()
                        val y = (rand.nextInt(12) - 6).toDouble()
                        val dlat = x / earth
                        val dlng = y / (earth * cos(pi * lat / 180.0))
                        newlat = lat + (dlat * 180.0 / pi)
                        newlng = lng + (dlng * 180.0 / pi)
                    } else {
                        newlat = lat
                        newlng = lng
                    }
                    
                    accuracy = settings.accuracy?.toFloatOrNull() ?: 10f
                    
                    val syncedBearing = SpeedSyncManager.getBearing()
                    bearing = if (syncedBearing != 0f) syncedBearing else settings.getBearing
                    
                    val syncedSpeed = SpeedSyncManager.getActualSpeed()
                    speed = if (syncedSpeed > 0f) {
                        SpeedSyncManager.speedKmhToMs(syncedSpeed)
                    } else {
                        settings.getSpeed
                    }
                }
            }
        } catch (e: Exception) { }
    }
    
    private fun setLocationFields(location: Location) {
        try {
            location.latitude = newlat
            location.longitude = newlng
            location.bearing = bearing
            location.speed = speed
            location.accuracy = accuracy
            location.time = System.currentTimeMillis()
            location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                location.verticalAccuracyMeters = 0.5f
                location.bearingAccuracyDegrees = 5f
                location.speedAccuracyMetersPerSecond = 0.1f
            }

            try {
                HiddenApiBypass.invoke(Location::class.java, location, "setFieldsMask", 255)
            } catch (e: Throwable) {
                try {
                    val m = Location::class.java.getDeclaredMethod("setFieldsMask", Int::class.javaPrimitiveType)
                    m.isAccessible = true
                    m.invoke(location, 255)
                } catch (e2: Throwable) {}
            }
            
            try {
                HiddenApiBypass.invoke(location.javaClass, location, "setIsFromMockProvider", false)
            } catch (e: Exception) { }
            
            val extras = location.extras ?: Bundle()
            extras.remove("mockLocation")
            extras.putBoolean("mockLocation", false)
            extras.putInt("satellites", 12)
            location.extras = extras
        } catch (e: Throwable) { }
    }

    @SuppressLint("NewApi")
    fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        updateLocation(true)

        // Tầng 1: Fake Settings toàn cục cho mọi app trong scope (Local check bypass)
        hookSystemSettings(lpparam)

        // Tầng 2: Hook đặc thù cho từng loại package
        if (lpparam.packageName == "android") {
            hookSystemServer(lpparam)
            return
        }

        if (ignorePkg.contains(lpparam.packageName)) return

        if (lpparam.packageName == "com.google.android.gms") {
            hookGmsCore(lpparam)
        }

        // Tầng 3: Hook sâu mức ứng dụng (Getters, Wifi, BT, Cell, Geocoder, Request Updates)
        hookApplicationLevel(lpparam)
    }

    private fun hookSystemSettings(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val settingsSecure = Settings.Secure::class.java
            
            // Hook tất cả các overload của getInt/getString trong Settings.Secure
            XposedBridge.hookAllMethods(settingsSecure, "getInt", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (isHookActive() && settings.isAccuracySpoofEnabled) {
                        val name = param.args[1] as? String ?: return
                        when (name) {
                            "location_mode" -> param.result = 3 // LOCATION_MODE_HIGH_ACCURACY
                            "location_accuracy_enabled" -> param.result = 1
                            "network_location_opt_in" -> param.result = 1
                        }
                    }
                }
            })

            XposedBridge.hookAllMethods(settingsSecure, "getString", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (isHookActive() && settings.isAccuracySpoofEnabled) {
                        val name = param.args[1] as? String ?: return
                        if (name == "location_providers_allowed") {
                            param.result = "gps,network"
                        }
                    }
                }
            })

            // Hook Settings.Global for WiFi/BT scan always enabled
            try {
                val settingsGlobal = Settings.Global::class.java
                XposedBridge.hookAllMethods(settingsGlobal, "getInt", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isHookActive()) return
                        val name = param.args[1] as? String ?: return
                        when (name) {
                            "wifi_scan_always_enabled" -> {
                                if (settings.isWifiScanSpoofEnabled) param.result = 1
                            }
                            "ble_scan_always_enabled" -> {
                                if (settings.isBtScanSpoofEnabled) param.result = 1
                            }
                            "assisted_gps_enabled" -> {
                                if (settings.isAccuracySpoofEnabled) param.result = 1
                            }
                        }
                    }
                })
            } catch (e: Throwable) { }
        } catch (e: Throwable) { }
    }

    private fun hookSystemServer(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val lmsClassName = if (Build.VERSION.SDK_INT >= 34) 
                "com.android.server.location.LocationManagerService" 
                else "com.android.server.LocationManagerService"
            val lmsClass = XposedHelpers.findClassIfExists(lmsClassName, lpparam.classLoader)
            
            if (lmsClass != null) {
                XposedBridge.hookAllMethods(lmsClass, "getLastLocation", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        updateLocation()
                        if (isStartedCache) {
                            val loc = Location(LocationManager.GPS_PROVIDER)
                            setLocationFields(loc)
                            param.result = loc
                        }
                    }
                })
            }

            try {
                val lpmClass = XposedHelpers.findClassIfExists("com.android.server.location.provider.LocationProviderManager", lpparam.classLoader)
                if (lpmClass != null) {
                    XposedBridge.hookAllMethods(lpmClass, "onReportLocation", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            updateLocation()
                            if (isStartedCache) {
                                val arg = param.args[0] ?: return
                                if (arg is Location) {
                                    setLocationFields(arg)
                                } else if (arg.javaClass.name.contains("LocationResult")) {
                                    val locations = XposedHelpers.callMethod(arg, "getLocations") as? List<*>
                                    locations?.forEach { (it as? Location)?.let { loc -> setLocationFields(loc) } }
                                }
                            }
                        }
                    })
                }
            } catch (e: Throwable) { }
        } catch (e: Throwable) { }
    }

    private fun hookGmsCore(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val callbackClass = XposedHelpers.findClassIfExists("com.google.android.gms.location.internal.IFusedLocationProviderCallback.Stub", lpparam.classLoader)
            if (callbackClass != null) {
                XposedBridge.hookAllMethods(callbackClass, "onLocationResult", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        updateLocation()
                        if (isStartedCache) {
                            val result = param.args[0] ?: return
                            try {
                                val locations = XposedHelpers.callMethod(result, "getLocations") as? List<*>
                                locations?.forEach { (it as? Location)?.let { loc -> setLocationFields(loc) } }
                            } catch (e: Throwable) { }
                        }
                    }
                })
            }
        } catch (e: Throwable) { }
    }

    private fun hookApplicationLevel(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val locClass = Location::class.java
            val lmClass = XposedHelpers.findClassIfExists("android.location.LocationManager", lpparam.classLoader) ?: return

            // Hook Getters của object Location
            XposedHelpers.findAndHookMethod(locClass, "getLatitude", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { if (isHookActive()) param.result = newlat }
            })
            XposedHelpers.findAndHookMethod(locClass, "getLongitude", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { if (isHookActive()) param.result = newlng }
            })
            XposedHelpers.findAndHookMethod(locClass, "isFromMockProvider", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { if (isHookActive()) param.result = false }
            })

            // Chặn app copy location gốc
            XposedHelpers.findAndHookMethod(locClass, "set", Location::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (isHookActive()) {
                        val src = param.args[0] as? Location ?: return
                        setLocationFields(src)
                    }
                }
            })

            // Tự động fake kết quả trả về từ LocationManager
            XposedBridge.hookAllMethods(lmClass, "getLastKnownLocation", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (isHookActive()) {
                        val loc = Location(param.args[0]?.toString() ?: LocationManager.GPS_PROVIDER)
                        setLocationFields(loc)
                        param.result = loc
                    }
                }
            })

            XposedBridge.hookAllMethods(lmClass, "requestLocationUpdates", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    updateLocation()
                    if (!isStartedCache) return
                    
                    for (i in param.args.indices) {
                        val arg = param.args[i] ?: continue
                        if (arg is android.location.LocationListener) {
                            val original = arg
                            if (original.javaClass.name.contains("io.github.mwarevn")) return
                            
                            param.args[i] = object : android.location.LocationListener {
                                override fun onLocationChanged(location: Location) {
                                    updateLocation()
                                    setLocationFields(location)
                                    try { original.onLocationChanged(location) } catch (e: Throwable) {}
                                }
                                override fun onLocationChanged(locations: List<Location>) {
                                    locations.forEach { onLocationChanged(it) }
                                }
                                @Suppress("DEPRECATION")
                                override fun onStatusChanged(p: String?, s: Int, e: Bundle?) = try { original.onStatusChanged(p, s, e) } catch (e: Throwable) {}
                                override fun onProviderEnabled(p: String) = try { original.onProviderEnabled(p) } catch (e: Throwable) {}
                                override fun onProviderDisabled(p: String) = try { original.onProviderDisabled(p) } catch (e: Throwable) {}
                            }
                            break
                        }
                    }
                }
            })

            // Chống định vị qua Wifi (Nếu option được bật)
            if (settings.isNetworkSimEnabled) {
                try {
                    val wmClass = XposedHelpers.findClassIfExists("android.net.wifi.WifiManager", lpparam.classLoader)
                    if (wmClass != null) {
                        XposedHelpers.findAndHookMethod(wmClass, "getScanResults", object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (isHookActive()) param.result = emptyList<android.net.wifi.ScanResult>()
                            }
                        })
                    }
                } catch (e: Throwable) {}
            }

            // Chống định vị qua Bluetooth (Nếu option được bật)
            if (settings.isBluetoothSpoofEnabled) {
                hookBluetooth(lpparam)
            }

            // Chống định vị qua Cell Tower (Nếu option được bật)
            if (settings.isCellSpoofEnabled) {
                hookCellTower(lpparam)
            }

            // Hook Geocoder (Nếu option được bật)
            if (settings.isGeocoderSpoofEnabled) {
                hookGeocoder(lpparam)
            }

            // Hook Location.isMock() (API 31+)
            try {
                XposedHelpers.findAndHookMethod(locClass, "isMock", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isHookActive()) param.result = false
                    }
                })
            } catch (e: Throwable) { /* Method may not exist on older APIs */ }

        } catch (e: Throwable) { }
    }

    /**
     * Hook Bluetooth scanning to prevent BLE/Classic-based location fingerprinting.
     */
    private fun hookBluetooth(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook BluetoothAdapter.startDiscovery() -> return false
            val btAdapterClass = XposedHelpers.findClassIfExists(
                "android.bluetooth.BluetoothAdapter", lpparam.classLoader
            )
            if (btAdapterClass != null) {
                XposedBridge.hookAllMethods(btAdapterClass, "startDiscovery", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isHookActive()) param.result = false
                    }
                })
                // Hook getBluetoothLeScanner() -> return null
                try {
                    XposedHelpers.findAndHookMethod(btAdapterClass, "getBluetoothLeScanner", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (isHookActive()) param.result = null
                        }
                    })
                } catch (e: Throwable) {}
            }

            // Hook BluetoothLeScanner.startScan to do nothing
            val bleScannerClass = XposedHelpers.findClassIfExists(
                "android.bluetooth.le.BluetoothLeScanner", lpparam.classLoader
            )
            if (bleScannerClass != null) {
                XposedBridge.hookAllMethods(bleScannerClass, "startScan", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isHookActive()) param.result = null
                    }
                })
            }
        } catch (e: Throwable) {
            XposedBridge.log("GPS Setter: Failed to hook Bluetooth: ${e.message}")
        }
    }

    /**
     * Hook Cell tower APIs to prevent cell-based location triangulation.
     */
    private fun hookCellTower(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val tmClass = XposedHelpers.findClassIfExists(
                "android.telephony.TelephonyManager", lpparam.classLoader
            ) ?: return

            // getCellLocation() -> null
            try {
                XposedBridge.hookAllMethods(tmClass, "getCellLocation", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isHookActive()) param.result = null
                    }
                })
            } catch (e: Throwable) {}

            // getAllCellInfo() -> empty list
            try {
                XposedBridge.hookAllMethods(tmClass, "getAllCellInfo", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isHookActive()) param.result = emptyList<Any>()
                    }
                })
            } catch (e: Throwable) {}

            // getNeighboringCellInfo() -> empty list
            try {
                @Suppress("DEPRECATION")
                XposedBridge.hookAllMethods(tmClass, "getNeighboringCellInfo", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isHookActive()) param.result = emptyList<Any>()
                    }
                })
            } catch (e: Throwable) {}
        } catch (e: Throwable) {
            XposedBridge.log("GPS Setter: Failed to hook CellTower: ${e.message}")
        }
    }

    /**
     * Hook Geocoder.getFromLocation() to return address matching fake coordinates.
     */
    private fun hookGeocoder(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val geocoderClass = XposedHelpers.findClassIfExists(
                "android.location.Geocoder", lpparam.classLoader
            ) ?: return

            // Hook getFromLocation to replace lat/lng arguments with fake location
            XposedBridge.hookAllMethods(geocoderClass, "getFromLocation", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (isHookActive() && param.args.size >= 2) {
                        updateLocation()
                        param.args[0] = newlat
                        param.args[1] = newlng
                    }
                }
            })
        } catch (e: Throwable) {
            XposedBridge.log("GPS Setter: Failed to hook Geocoder: ${e.message}")
        }
    }

    private fun isHookActive(): Boolean {
        updateLocation()
        return isStartedCache
    }
}
