package io.github.mwarevn.movingsimulation.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import io.github.mwarevn.movingsimulation.BuildConfig
import io.github.mwarevn.movingsimulation.gsApp
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch


@SuppressLint("WorldReadableFiles")
object PrefManager   {

    private const val START = "start"
    private const val LATITUDE = "latitude"
    private const val LONGITUDE = "longitude"
    private const val BEARING = "bearing"
    private const val SPEED = "speed"
    private const val HOOKED_SYSTEM = "system_hooked"
    private const val RANDOM_POSITION = "random_position"
    private const val ACCURACY_SETTING = "accuracy_level"
    private const val MAP_TYPE = "map_type"
    private const val DARK_THEME = "dark_theme"
    private const val DISABLE_UPDATE = "update_disabled"
    private const val ENABLE_JOYSTICK = "joystick_enabled"
    private const val MAPBOX_API_KEY = "mapbox_api_key"
    private const val VEHICLE_TYPE = "vehicle_type"
    
    // Anti-Detection Hooks Configuration
    private const val HOOK_IS_FROM_MOCK_PROVIDER = "hook_is_from_mock_provider"
    private const val HOOK_MOCK_LOCATION_CHECK = "hook_mock_location_check"
    private const val HOOK_BUILD_FIELDS = "hook_build_fields"
    private const val HOOK_STACK_TRACE = "hook_stack_trace"
    private const val HOOK_PACKAGE_MANAGER_SAFE = "hook_package_manager_safe"
    private const val HOOK_CLASS_LOADER_SAFE = "hook_class_loader_safe"
    private const val HOOK_APPLICATION_INFO = "hook_application_info"
    private const val HOOK_SYSTEM_PROPERTIES = "hook_system_properties"
    private const val HOOK_CLASS_FOR_NAME = "hook_class_for_name"
    private const val HOOK_CLASS_LOADER = "hook_class_loader"
    private const val HOOK_PACKAGE_MANAGER = "hook_package_manager"
    private const val HOOK_NATIVE_LIBRARY = "hook_native_library"
    private const val HOOK_MAP_VIEW = "hook_map_view"


    private val pref: SharedPreferences by lazy {
        try {
            val prefsFile = "${BuildConfig.APPLICATION_ID}_prefs"
            gsApp.getSharedPreferences(
                prefsFile,
                Context.MODE_WORLD_READABLE
            )
        }catch (e:SecurityException){
            val prefsFile = "${BuildConfig.APPLICATION_ID}_prefs"
            gsApp.getSharedPreferences(
                prefsFile,
                Context.MODE_PRIVATE
            )
        }

    }


    val isStarted : Boolean
        get() = pref.getBoolean(START, false)

    val getLat : Double
        get() = pref.getFloat(LATITUDE, 40.7128F).toDouble()

    val getLng : Double
        get() = pref.getFloat(LONGITUDE, -74.0060F).toDouble()

    val getBearing : Float
        get() = pref.getFloat(BEARING, 0F)

    val getSpeed : Float
        get() = pref.getFloat(SPEED, 0F)

    var isSystemHooked : Boolean
        get() = pref.getBoolean(HOOKED_SYSTEM, false)
        set(value) { pref.edit().putBoolean(HOOKED_SYSTEM,value).apply() }

    var isRandomPosition :Boolean
        get() = pref.getBoolean(RANDOM_POSITION, false)
        set(value) { pref.edit().putBoolean(RANDOM_POSITION, value).apply() }

    var accuracy : String?
        get() = pref.getString(ACCURACY_SETTING,"10")
        set(value) { pref.edit().putString(ACCURACY_SETTING,value).apply()}

    var mapType : Int
        get() = pref.getInt(MAP_TYPE,1)
        set(value) { pref.edit().putInt(MAP_TYPE,value).apply()}

    var darkTheme: Int
        get() = pref.getInt(DARK_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = pref.edit().putInt(DARK_THEME, value).apply()

    var isUpdateDisabled: Boolean
        get() = pref.getBoolean(DISABLE_UPDATE, false)
        set(value) = pref.edit().putBoolean(DISABLE_UPDATE, value).apply()

    var isJoystickEnabled: Boolean
        get() = pref.getBoolean(ENABLE_JOYSTICK, false)
        set(value) = pref.edit().putBoolean(ENABLE_JOYSTICK, value).apply()

    var mapBoxApiKey: String?
        get() = pref.getString(MAPBOX_API_KEY, null)
        set(value) = pref.edit().putString(MAPBOX_API_KEY, value).apply()

    var vehicleType: String
        get() = pref.getString(VEHICLE_TYPE, "MOTORBIKE") ?: "MOTORBIKE"
        set(value) = pref.edit().putString(VEHICLE_TYPE, value).apply()

    // ============================================================
    // Anti-Detection Hooks Configuration
    // ============================================================
    
    // TIER 1: SAFE hooks (enabled by default)
    var hookIsFromMockProvider: Boolean
        get() = pref.getBoolean(HOOK_IS_FROM_MOCK_PROVIDER, true)
        set(value) = pref.edit().putBoolean(HOOK_IS_FROM_MOCK_PROVIDER, value).apply()
    
    var hookMockLocationCheck: Boolean
        get() = pref.getBoolean(HOOK_MOCK_LOCATION_CHECK, true)
        set(value) = pref.edit().putBoolean(HOOK_MOCK_LOCATION_CHECK, value).apply()
    
    var hookBuildFields: Boolean
        get() = pref.getBoolean(HOOK_BUILD_FIELDS, true)
        set(value) = pref.edit().putBoolean(HOOK_BUILD_FIELDS, value).apply()
    
    var hookStackTrace: Boolean
        get() = pref.getBoolean(HOOK_STACK_TRACE, true)
        set(value) = pref.edit().putBoolean(HOOK_STACK_TRACE, value).apply()
    
    // TIER 2: MODERATE risk hooks (enabled by default)
    var hookPackageManagerSafe: Boolean
        get() = pref.getBoolean(HOOK_PACKAGE_MANAGER_SAFE, true)
        set(value) = pref.edit().putBoolean(HOOK_PACKAGE_MANAGER_SAFE, value).apply()
    
    var hookClassLoaderSafe: Boolean
        get() = pref.getBoolean(HOOK_CLASS_LOADER_SAFE, true)
        set(value) = pref.edit().putBoolean(HOOK_CLASS_LOADER_SAFE, value).apply()
    
    var hookApplicationInfo: Boolean
        get() = pref.getBoolean(HOOK_APPLICATION_INFO, true)
        set(value) = pref.edit().putBoolean(HOOK_APPLICATION_INFO, value).apply()
    
    var hookSystemProperties: Boolean
        get() = pref.getBoolean(HOOK_SYSTEM_PROPERTIES, true)
        set(value) = pref.edit().putBoolean(HOOK_SYSTEM_PROPERTIES, value).apply()
    
    // TIER 3: RISKY hooks (disabled by default)
    var hookClassForName: Boolean
        get() = pref.getBoolean(HOOK_CLASS_FOR_NAME, false)
        set(value) = pref.edit().putBoolean(HOOK_CLASS_FOR_NAME, value).apply()
    
    var hookClassLoader: Boolean
        get() = pref.getBoolean(HOOK_CLASS_LOADER, false)
        set(value) = pref.edit().putBoolean(HOOK_CLASS_LOADER, value).apply()
    
    var hookPackageManager: Boolean
        get() = pref.getBoolean(HOOK_PACKAGE_MANAGER, false)
        set(value) = pref.edit().putBoolean(HOOK_PACKAGE_MANAGER, value).apply()
    
    var hookNativeLibrary: Boolean
        get() = pref.getBoolean(HOOK_NATIVE_LIBRARY, false)
        set(value) = pref.edit().putBoolean(HOOK_NATIVE_LIBRARY, value).apply()
    
    var hookMapView: Boolean
        get() = pref.getBoolean(HOOK_MAP_VIEW, false)
        set(value) = pref.edit().putBoolean(HOOK_MAP_VIEW, value).apply()
    
    /**
     * Reset all anti-detection hooks to default safe settings
     */
    fun resetAntiDetectionToDefault() {
        runInBackground {
            val editor = pref.edit()
            // TIER 1: SAFE (enabled)
            editor.putBoolean(HOOK_IS_FROM_MOCK_PROVIDER, true)
            editor.putBoolean(HOOK_MOCK_LOCATION_CHECK, true)
            editor.putBoolean(HOOK_BUILD_FIELDS, true)
            editor.putBoolean(HOOK_STACK_TRACE, true)
            // TIER 2: MODERATE (enabled)
            editor.putBoolean(HOOK_PACKAGE_MANAGER_SAFE, true)
            editor.putBoolean(HOOK_CLASS_LOADER_SAFE, true)
            editor.putBoolean(HOOK_APPLICATION_INFO, true)
            editor.putBoolean(HOOK_SYSTEM_PROPERTIES, true)
            // TIER 3: RISKY (disabled)
            editor.putBoolean(HOOK_CLASS_FOR_NAME, false)
            editor.putBoolean(HOOK_CLASS_LOADER, false)
            editor.putBoolean(HOOK_PACKAGE_MANAGER, false)
            editor.putBoolean(HOOK_NATIVE_LIBRARY, false)
            editor.putBoolean(HOOK_MAP_VIEW, false)
            editor.apply()
        }
    }

    fun update(start:Boolean, la: Double, ln: Double, bearing: Float = 0F, speed: Float = 0F) {
        runInBackground {
            val prefEditor = pref.edit()
            prefEditor.putFloat(LATITUDE, la.toFloat())
            prefEditor.putFloat(LONGITUDE, ln.toFloat())
            prefEditor.putFloat(BEARING, bearing)
            prefEditor.putFloat(SPEED, speed)
            prefEditor.putBoolean(START, start)
            prefEditor.apply()
        }

    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun runInBackground(method: suspend () -> Unit){
        GlobalScope.launch(Dispatchers.IO) {
            method.invoke()
        }
    }

}