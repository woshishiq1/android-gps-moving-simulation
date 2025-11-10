package io.github.mwarevn.movingsimulation.xposed
import de.robv.android.xposed.XSharedPreferences
import io.github.mwarevn.movingsimulation.BuildConfig

class Xshare {

    private var xPref: XSharedPreferences? = null

    private fun pref() : XSharedPreferences {
        xPref = XSharedPreferences(BuildConfig.APPLICATION_ID,"${BuildConfig.APPLICATION_ID}_prefs")
        return xPref as XSharedPreferences
    }

    val isStarted : Boolean
    get() = pref().getBoolean(
        "start",
        false
    )

    val getLat: Double
    get() = pref().getFloat(
        "latitude",
        45.0000000.toFloat()
    ).toDouble()


    val getLng : Double
    get() = pref().getFloat(
        "longitude",
        0.0000000.toFloat()
    ).toDouble()

    val getBearing : Float
    get() = pref().getFloat(
        "bearing",
        0F
    )

    val getSpeed : Float
    get() = pref().getFloat(
        "speed",
        0F
    )

    val isHookedSystem : Boolean
    get() = pref().getBoolean(
        "system_hooked",
        true
    )

    val isRandomPosition :Boolean
    get() = pref().getBoolean(
        "random_position",
        false
    )

    val accuracy : String?
    get() = pref().getString("accuracy_level","10")

    val reload = pref().reload()

}