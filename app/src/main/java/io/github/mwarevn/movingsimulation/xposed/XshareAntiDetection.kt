package io.github.mwarevn.movingsimulation.xposed

import de.robv.android.xposed.XSharedPreferences
import io.github.mwarevn.movingsimulation.BuildConfig

/**
 * XShared preferences for Anti-Detection hooks configuration
 * Allows users to enable/disable individual hooks via Settings
 */
class XshareAntiDetection {

    private var xPref: XSharedPreferences? = null

    private fun pref(): XSharedPreferences {
        xPref = XSharedPreferences(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}_prefs")
        xPref?.makeWorldReadable()
        return xPref as XSharedPreferences
    }

    // ============================================================
    // TIER 1: SAFE HOOKS (Enabled by default)
    // ============================================================

    val isHookIsFromMockProvider: Boolean
        get() = pref().getBoolean("hook_is_from_mock_provider", true)

    val isHookMockLocationCheck: Boolean
        get() = pref().getBoolean("hook_mock_location_check", true)

    val isHookBuildFields: Boolean
        get() = pref().getBoolean("hook_build_fields", true)

    val isHookStackTrace: Boolean
        get() = pref().getBoolean("hook_stack_trace", true)

    // ============================================================
    // TIER 2: MODERATE RISK HOOKS (Enabled by default)
    // ============================================================

    val isHookPackageManagerSafe: Boolean
        get() = pref().getBoolean("hook_package_manager_safe", true)

    val isHookClassLoaderSafe: Boolean
        get() = pref().getBoolean("hook_class_loader_safe", true)

    val isHookApplicationInfo: Boolean
        get() = pref().getBoolean("hook_application_info", true)

    val isHookSystemProperties: Boolean
        get() = pref().getBoolean("hook_system_properties", true)

    // ============================================================
    // TIER 3: RISKY HOOKS (Disabled by default)
    // ============================================================

    val isHookClassForName: Boolean
        get() = pref().getBoolean("hook_class_for_name", false)

    val isHookClassLoader: Boolean
        get() = pref().getBoolean("hook_class_loader", false)

    val isHookPackageManager: Boolean
        get() = pref().getBoolean("hook_package_manager", false)

    val isHookNativeLibrary: Boolean
        get() = pref().getBoolean("hook_native_library", false)

    val isHookMapView: Boolean
        get() = pref().getBoolean("hook_map_view", false)

    // ============================================================
    // TIER 4: ADVANCED FULL FLAVOR HOOKS (Disabled by default)
    // These are powerful hooks for maximum bypass but require caution
    // ============================================================

    val isHookSensorSpoof: Boolean
        get() = pref().getBoolean("hook_sensor_spoof", false)

    val isHookNetworkFake: Boolean
        get() = pref().getBoolean("hook_network_fake", false)

    val isHookAdvancedRandomize: Boolean
        get() = pref().getBoolean("hook_advanced_randomize", false)

    init {
        pref().reload()
    }
}
