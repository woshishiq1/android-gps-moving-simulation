package io.github.mwarevn.movingsimulation.xposed

import de.robv.android.xposed.XSharedPreferences
import io.github.mwarevn.movingsimulation.BuildConfig

/**
 * XShared preferences for Advanced Anti-Detection features
 * Allows users to enable/disable advanced features via Settings
 */
class XshareAntiDetection {

    private var xPref: XSharedPreferences? = null

    private fun pref(): XSharedPreferences {
        xPref = XSharedPreferences(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}_prefs")
        xPref?.makeWorldReadable()
        return xPref as XSharedPreferences
    }

    // ============================================================
    // Advanced Anti-Detection Features
    // ============================================================

    /**
     * Sensor spoofing: Synchronizes accelerometer, gyroscope, and magnetometer
     * with GPS movement for realistic device motion simulation
     */
    val isEnableSensorSpoof: Boolean
        get() = pref().getBoolean("enable_sensor_spoof", false)

    /**
     * Network simulation: Fakes cell tower and WiFi data to match GPS location
     * Helps bypass apps that verify location via network triangulation
     */
    val isEnableNetworkSimulation: Boolean
        get() = pref().getBoolean("enable_network_simulation", false)

    /**
     * Advanced randomization: Adds realistic variations to GPS, sensors, and timing
     * to resist device fingerprinting and machine learning detection
     */
    val isEnableAdvancedRandomization: Boolean
        get() = pref().getBoolean("enable_advanced_randomization", false)

    init {
        pref().reload()
    }
}
