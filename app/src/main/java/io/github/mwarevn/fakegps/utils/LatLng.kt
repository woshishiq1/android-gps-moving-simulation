package io.github.mwarevn.fakegps.utils

import android.os.Parcelable
import androidx.annotation.Keep
import kotlinx.parcelize.Parcelize

/**
 * A custom Location coordinate class to decouple the app's core logic
 * from Google Maps SDK (io.github.mwarevn.fakegps.utils.LatLng).
 */
@Keep
@Parcelize
data class LatLng(
    var latitude: Double,
    var longitude: Double
) : Parcelable {
    
    // Fallback for distance calculation easily without adding Android Location overhead everywhere
    fun distanceTo(other: LatLng): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            this.latitude, this.longitude,
            other.latitude, other.longitude,
            results
        )
        return results[0]
    }
}
