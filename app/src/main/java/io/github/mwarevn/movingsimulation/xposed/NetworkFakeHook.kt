package io.github.mwarevn.movingsimulation.xposed

import android.content.Context
import android.location.Location
import android.net.wifi.ScanResult
import android.os.Build
import android.telephony.CellInfo
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.mwarevn.movingsimulation.BuildConfig
import io.github.mwarevn.movingsimulation.utils.PolylineUtils
import com.google.android.gms.maps.model.LatLng
import kotlin.math.abs

/**
 * NetworkFakeHook - Network-based location spoofing for ML detection bypass
 *
 * ⚠️ LIGHTWEIGHT IMPLEMENTATION FOR STABILITY:
 * This hook ONLY suppresses geofence/proximity alerts to prevent location exposure.
 * Does NOT modify Wi-Fi or cellular data to avoid bootloop issues.
 *
 * Detection Bypass:
 * - Prevents geofence triggers that would reveal spoofed locations outside allowed areas
 * - Suppresses proximity alerts that conflict with spoofed movement
 * - Does NOT fake Wi-Fi APs or cell towers (causes system instability)
 *
 * Scope: ONLY affects app-level hooks (scoped apps)
 * - Package filter: ignores "android" (system) and BuildConfig.APPLICATION_ID (own app)
 * - Does NOT affect actual system network settings
 * - Minimal footprint for stability
 *
 * Technical Details:
 * - Only hooks LocationManager geofence/proximity methods
 * - Safely suppresses geofence registration without breaking system
 * - No interference with WifiManager or TelephonyManager
 */
object NetworkFakeHook {

    private const val TAG = "NetworkFake"
    private var lastNetworkUpdateTime = 0L

    fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Only hook at app level (scoped apps)
        // Skip system package and own app
        if (lpparam.packageName == "android" || lpparam.packageName == BuildConfig.APPLICATION_ID) {
            return
        }

        try {
            // Only hook Location Manager for geofence suppression
            hookGeofencing(lpparam)

            XposedBridge.log("$TAG: Initialized for package ${lpparam.packageName}")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to initialize: ${e.message}")
        }
    }

    /**
     * Hook Location Manager to suppress geofence triggers for spoofed locations
     * Prevents alerts when crossing boundaries while using fake GPS
     * SAFE: Only suppresses geofence registration, doesn't modify system data
     */
    private fun hookGeofencing(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val locationManagerClass = XposedHelpers.findClassIfExists(
                "android.location.LocationManager",
                lpparam.classLoader
            ) ?: return

            // Hook addProximityAlert() to suppress geofence
            try {
                XposedHelpers.findAndHookMethod(
                    locationManagerClass,
                    "addProximityAlert",
                    Double::class.java,
                    Double::class.java,
                    Float::class.java,
                    Long::class.java,
                    android.app.PendingIntent::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            // Suppress geofence registration to prevent alerts from spoofed locations
                            XposedBridge.log("$TAG: Suppressed geofence registration")
                            param.result = null
                        }
                    }
                )
            } catch (e: Throwable) {
                // Ignore if method doesn't exist on this Android version
            }

            // Hook removeProximityAlert() to suppress geofence removal
            try {
                XposedHelpers.findAndHookMethod(
                    locationManagerClass,
                    "removeProximityAlert",
                    android.app.PendingIntent::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            // Safely handle removal request
                            param.result = null
                        }
                    }
                )
            } catch (e: Throwable) {
                // Ignore if method doesn't exist
            }

        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook geofencing: ${e.message}")
        }
    }
}
