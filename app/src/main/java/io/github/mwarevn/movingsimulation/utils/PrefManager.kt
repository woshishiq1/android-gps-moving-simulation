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
    private const val CAMERA_BEARING = "camera_bearing" // Camera rotation angle for joystick screen-relative movement
    private const val HOOKED_SYSTEM = "system_hooked"
    private const val RANDOM_POSITION = "random_position"
    private const val ACCURACY_SETTING = "accuracy_level"
    private const val MAP_TYPE = "map_type"
    private const val DARK_THEME = "dark_theme"
    private const val DISABLE_UPDATE = "update_disabled"
    private const val ENABLE_JOYSTICK = "joystick_enabled"
    private const val MAPBOX_API_KEY = "mapbox_api_key"
    private const val VEHICLE_TYPE = "vehicle_type"
    
    // Advanced Anti-Detection Features
    private const val ENABLE_SENSOR_SPOOF = "enable_sensor_spoof"
    private const val ENABLE_NETWORK_SIMULATION = "enable_network_simulation"
    private const val ENABLE_ADVANCED_RANDOMIZATION = "enable_advanced_randomization"
    private const val AUTO_CURVE_SPEED = "auto_curve_speed"
    private const val NAV_CONTROLS_EXPANDED = "nav_controls_expanded"

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

    // Expose SharedPreferences for listeners
    val sharedPreferences: SharedPreferences
        get() = pref

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

    var cameraBearing : Float
        get() = pref.getFloat(CAMERA_BEARING, 0F)
        set(value) { pref.edit().putFloat(CAMERA_BEARING, value).apply() }

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
    // Advanced Anti-Detection Features
    // ============================================================
    
    /**
     * Enable sensor spoofing to simulate realistic device movement
     * Includes: accelerometer, gyroscope, magnetometer synchronization with GPS
     */
    var enableSensorSpoof: Boolean
        get() = pref.getBoolean(ENABLE_SENSOR_SPOOF, false)
        set(value) = pref.edit().putBoolean(ENABLE_SENSOR_SPOOF, value).apply()
    
    /**
     * Enable network simulation to fake cell tower and WiFi data
     * Helps apps that verify location via network triangulation
     */
    var enableNetworkSimulation: Boolean
        get() = pref.getBoolean(ENABLE_NETWORK_SIMULATION, false)
        set(value) = pref.edit().putBoolean(ENABLE_NETWORK_SIMULATION, value).apply()
    
    /**
     * Enable advanced randomization for device fingerprinting resistance
     * Adds realistic variations to GPS, sensors, and timing patterns
     */
    var enableAdvancedRandomization: Boolean
        get() = pref.getBoolean(ENABLE_ADVANCED_RANDOMIZATION, false)
        set(value) = pref.edit().putBoolean(ENABLE_ADVANCED_RANDOMIZATION, value).apply()
    
    // Auto curve speed - automatically reduce speed when taking curves
    var autoCurveSpeed: Boolean
        get() = pref.getBoolean(AUTO_CURVE_SPEED, true)
        set(value) = pref.edit().putBoolean(AUTO_CURVE_SPEED, value).apply()
    
    // Navigation controls expanded state - default is expanded (false = collapsed, true = expanded)
    var navControlsExpanded: Boolean
        get() = pref.getBoolean(NAV_CONTROLS_EXPANDED, true)
        set(value) = pref.edit().putBoolean(NAV_CONTROLS_EXPANDED, value).apply()
    
    /**
     * Reset advanced anti-detection features to default settings (all disabled)
     */
    fun resetAntiDetectionToDefault() {
        runInBackground {
            val editor = pref.edit()
            // All advanced features disabled by default for safety
            editor.putBoolean(ENABLE_SENSOR_SPOOF, false)
            editor.putBoolean(ENABLE_NETWORK_SIMULATION, false)
            editor.putBoolean(ENABLE_ADVANCED_RANDOMIZATION, false)
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