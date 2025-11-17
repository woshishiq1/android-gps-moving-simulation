package io.github.mwarevn.movingsimulation.xposed

import android.content.Context
import android.location.Location
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.os.Build
import android.telephony.CellInfo
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.mwarevn.movingsimulation.BuildConfig
import kotlin.math.abs
import kotlin.random.Random

/**
 * NetworkFakeHook - Advanced WiFi spoofing for ML detection bypass
 *
 * OPTIMIZED IMPLEMENTATION FOR STABILITY:
 * This hook intercepts WiFi data and provides fake information that matches
 * the spoofed GPS location from LocationHook. Cell Tower data is suppressed
 * for stability (direct construction causes API issues).
 *
 * Detection Bypass:
 * - Apps cross-check GPS location with WiFi SSID and BSSID
 * - Our fake WiFi data matches the spoofed location perfectly
 * - Prevents "GPS in Vietnam but WiFi shows US" detection
 * - Cell Tower data is suppressed to prevent inconsistencies
 *
 * Network Data Spoofed:
 * - WiFi SSID: Generated based on lat/lng hash
 * - WiFi BSSID: Generated based on location
 * - WiFi Frequency: Mix of 2.4GHz and 5GHz
 * - Signal Strength: Realistic values with variation
 * - Cell Tower: Suppressed (returns empty to prevent detection)
 *
 * Scope: ONLY affects app-level hooks (scoped apps)
 * - Package filter: ignores "android" (system) and BuildConfig.APPLICATION_ID (own app)
 * - Does NOT affect real WiFi connectivity
 * - Apps still connect to internet normally
 */
object NetworkFakeHook {

    private const val TAG = "NetworkFake"
    
    // CRITICAL: Whitelist of system-critical packages to NEVER hook
    // Hooking these can cause bootloop or system instability
    private val CRITICAL_PACKAGES = setOf(
        "android",                          // System framework
        BuildConfig.APPLICATION_ID,         // Own app
        "com.android.systemui",             // System UI
        "com.android.settings",             // Settings app
        "com.android.providers.settings",   // Settings provider
        "com.android.wifi",                 // WiFi service
        "com.android.server.wifi",          // WiFi server
        "com.android.networkstack",         // Network stack
        "com.android.connectivity",         // Connectivity services
        "com.android.phone",                // Phone app
        "com.android.shell",                // ADB shell
        "com.google.android.gms",           // Google Play Services (can cause issues)
        "com.android.vending",              // Play Store
        "com.android.keychain",             // Key management
        "system_server"                     // System server process
    )
    
    // Cache for generated WiFi networks (to maintain consistency)
    private val wifiCache = mutableMapOf<String, List<FakeWifiNetwork>>()
    private var lastWifiUpdateTime = 0L
    private val WIFI_CACHE_DURATION = 5000L  // 5 seconds
    
    data class FakeWifiNetwork(
        val ssid: String,
        val bssid: String,
        val frequency: Int,
        val level: Int,
        val capabilities: String
    )
    
    fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        // CRITICAL SAFETY CHECK: Never hook system-critical packages
        if (CRITICAL_PACKAGES.any { lpparam.packageName.contains(it, ignoreCase = true) }) {
            XposedBridge.log("$TAG: Skipping critical package ${lpparam.packageName} (safety)")
            return
        }

        try {
            // Hook WiFi APIs (with extensive safety checks)
            hookWifiManager(lpparam)
            
            // Hook Cell Tower APIs (suppress for stability)
            hookTelephonyManager(lpparam)
            
            // Hook geofencing for safety
            hookGeofencing(lpparam)

            XposedBridge.log("$TAG: Initialized for package ${lpparam.packageName}")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to initialize: ${e.message}")
            // Continue execution - don't crash the app
        }
    }

    /**
     * Hook WifiManager to fake WiFi scan results
     * This makes WiFi SSIDs match the spoofed GPS location
     */
    private fun hookWifiManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val wifiManagerClass = XposedHelpers.findClassIfExists(
                "android.net.wifi.WifiManager",
                lpparam.classLoader
            ) ?: return
            
            // Hook getScanResults() - most important for location detection
            try {
                XposedHelpers.findAndHookMethod(
                    wifiManagerClass,
                    "getScanResults",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                // SAFETY: Check if GPS spoofing is actually active
                                val settings = Xshare()
                                if (!settings.isStarted) return
                                
                                // SAFETY: Don't modify if original result is null (WiFi off)
                                if (param.result == null) return
                                
                                // Get current spoofed location from LocationHook
                                val lat = LocationHook.newlat
                                val lng = LocationHook.newlng
                                
                                // SAFETY: Validate coordinates
                                if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
                                    XposedBridge.log("$TAG: Invalid coordinates, skipping")
                                    return
                                }
                                
                                // Generate fake WiFi networks based on location
                                val fakeWifiNetworks = generateFakeWifiNetworks(lat, lng)
                                
                                // Create fake ScanResult list
                                val fakeScanResults = fakeWifiNetworks.mapNotNull { network ->
                                    try {
                                        createFakeScanResult(network)
                                    } catch (e: Throwable) {
                                        null  // Skip failed networks
                                    }
                                }
                                
                                // Only replace if we successfully generated fake results
                                if (fakeScanResults.isNotEmpty()) {
                                    param.result = fakeScanResults
                                    
                                    // Log for debugging (very occasionally to avoid spam)
                                    if (System.currentTimeMillis() % 10000L < 500L) {
                                        XposedBridge.log("$TAG: Faked ${fakeScanResults.size} WiFi networks for location ($lat, $lng)")
                                    }
                                }
                            } catch (e: Throwable) {
                                // CRITICAL: Never throw - just log and continue
                                XposedBridge.log("$TAG: Error in getScanResults hook: ${e.message}")
                                // Don't modify param.result - let original value pass through
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                XposedBridge.log("$TAG: Failed to hook getScanResults: ${e.message}")
                // Continue - don't crash
            }
            
            // DISABLED: Hook getConnectionInfo() - TOO DANGEROUS, causes bootloop
            // Modifying active WiFi connection can break system WiFi services
            // getScanResults() is sufficient for location detection bypass
            /*
            try {
                XposedHelpers.findAndHookMethod(
                    wifiManagerClass,
                    "getConnectionInfo",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            // DISABLED FOR SAFETY
                        }
                    }
                )
            } catch (e: Throwable) {
                // Not critical if this fails
            }
            */
            XposedBridge.log("$TAG: getConnectionInfo() hook DISABLED for safety (prevents bootloop)")
            
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook WifiManager: ${e.message}")
        }
    }
    
    /**
     * Hook TelephonyManager to suppress cell tower data
     * Returning empty list is safer than trying to construct fake CellInfo objects
     * (which have package-private constructors and complex internal structures)
     */
    private fun hookTelephonyManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val telephonyManagerClass = XposedHelpers.findClassIfExists(
                "android.telephony.TelephonyManager",
                lpparam.classLoader
            ) ?: return
            
            // Hook getAllCellInfo() - return empty list for safety
            try {
                XposedHelpers.findAndHookMethod(
                    telephonyManagerClass,
                    "getAllCellInfo",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val settings = Xshare()
                                if (!settings.isStarted) return
                                
                                // Return empty list to suppress cell tower info
                                // This prevents "GPS location but wrong cell tower" detection
                                param.result = emptyList<CellInfo>()
                                
                                // Log occasionally
                                if (System.currentTimeMillis() % 15000L < 500L) {
                                    XposedBridge.log("$TAG: Suppressed cell tower info (safety)")
                                }
                            } catch (e: Throwable) {
                                // Silently fail
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                // Not critical if this fails
            }
            
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook TelephonyManager: ${e.message}")
        }
    }

    /**
     * Generate fake WiFi networks based on spoofed location
     * Creates consistent SSIDs and BSSIDs that look realistic
     */
    private fun generateFakeWifiNetworks(lat: Double, lng: Double): List<FakeWifiNetwork> {
        // Check cache
        val cacheKey = "${lat.toInt()}_${lng.toInt()}"
        val currentTime = System.currentTimeMillis()
        
        if (currentTime - lastWifiUpdateTime < WIFI_CACHE_DURATION && wifiCache.containsKey(cacheKey)) {
            return wifiCache[cacheKey] ?: emptyList()
        }
        
        lastWifiUpdateTime = currentTime
        
        // Generate 3-7 fake WiFi networks
        val numNetworks = Random.nextInt(3, 8)
        val networks = mutableListOf<FakeWifiNetwork>()
        
        for (i in 0 until numNetworks) {
            // Generate realistic SSID based on location
            val ssid = generateWifiSsid(lat, lng, i)
            
            // Generate BSSID (MAC address) based on location
            val bssid = generateBssid(lat, lng, i)
            
            // Random frequency (2.4GHz or 5GHz)
            val frequency = if (Random.nextBoolean()) {
                2412 + Random.nextInt(0, 12) * 5  // 2.4GHz channels
            } else {
                5180 + Random.nextInt(0, 24) * 20  // 5GHz channels
            }
            
            // Signal strength (-30 to -90 dBm, closer networks have stronger signal)
            val level = -40 - Random.nextInt(0, 50) - (i * 5)
            
            // Capabilities
            val capabilities = if (Random.nextBoolean()) {
                "[WPA2-PSK-CCMP][ESS]"
            } else {
                "[WPA-PSK-CCMP+TKIP][WPA2-PSK-CCMP+TKIP][ESS]"
            }
            
            networks.add(FakeWifiNetwork(ssid, bssid, frequency, level, capabilities))
        }
        
        wifiCache[cacheKey] = networks
        return networks
    }
    
    /**
     * Generate realistic WiFi SSID based on location
     */
    private fun generateWifiSsid(lat: Double, lng: Double, index: Int): String {
        val hash = abs((lat * 10000).toInt() xor (lng * 10000).toInt() xor index)
        
        val prefixes = listOf("WiFi", "TP-Link", "NETGEAR", "Linksys", "Asus", "Huawei", "Xiaomi", "Home", "Office")
        val prefix = prefixes[hash % prefixes.size]
        
        val suffix = (hash % 10000).toString().padStart(4, '0')
        return "$prefix-$suffix"
    }
    
    /**
     * Generate realistic BSSID (MAC address) based on location
     */
    private fun generateBssid(lat: Double, lng: Double, index: Int): String {
        val hash = abs((lat * 10000).toInt() xor (lng * 10000).toInt() xor (index * 256))
        
        // Common WiFi MAC prefixes
        val prefixes = listOf("00:11:22", "00:1A:2B", "00:1E:58", "00:24:01", "00:26:5A", "00:50:56")
        val prefix = prefixes[hash % prefixes.size]
        
        val part1 = ((hash shr 16) and 0xFF).toString(16).padStart(2, '0')
        val part2 = ((hash shr 8) and 0xFF).toString(16).padStart(2, '0')
        val part3 = (hash and 0xFF).toString(16).padStart(2, '0')
        
        return "$prefix:$part1:$part2:$part3"
    }
    
    /**
     * Create fake ScanResult object
     */
    private fun createFakeScanResult(network: FakeWifiNetwork): ScanResult? {
        return try {
            val scanResult = ScanResult()
            scanResult.SSID = network.ssid
            scanResult.BSSID = network.bssid
            scanResult.frequency = network.frequency
            scanResult.level = network.level
            scanResult.capabilities = network.capabilities
            scanResult.timestamp = System.currentTimeMillis() * 1000  // Microseconds
            scanResult
        } catch (e: Throwable) {
            null
        }
    }
    
    /**
     * Create WifiSsid object (internal Android class)
     */
    private fun createWifiSsid(ssid: String, classLoader: ClassLoader): Any? {
        return try {
            val wifiSsidClass = XposedHelpers.findClass("android.net.wifi.WifiSsid", classLoader)
            val createFromAsciiEncodedMethod = wifiSsidClass.getDeclaredMethod("createFromAsciiEncoded", String::class.java)
            createFromAsciiEncodedMethod.isAccessible = true
            createFromAsciiEncodedMethod.invoke(null, ssid)
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Hook Location Manager to suppress geofence triggers for spoofed locations
     * Prevents alerts when crossing boundaries while using fake GPS
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
                            val settings = Xshare()
                            if (!settings.isStarted) return
                            
                            // Suppress geofence registration to prevent alerts
                            param.result = null
                        }
                    }
                )
            } catch (e: Throwable) {
                // Ignore if method doesn't exist
            }

            // Hook removeProximityAlert() to suppress geofence removal
            try {
                XposedHelpers.findAndHookMethod(
                    locationManagerClass,
                    "removeProximityAlert",
                    android.app.PendingIntent::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val settings = Xshare()
                            if (!settings.isStarted) return
                            
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
