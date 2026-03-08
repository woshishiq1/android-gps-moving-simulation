package io.github.mwarevn.fakegps.domain.model

import android.os.Parcelable
import androidx.annotation.Keep
import kotlinx.parcelize.Parcelize

/**
 * Domain entity representing a coordinate.
 * Decouples logic from any specific Map SDK.
 */
@Keep
@Parcelize
data class LatLng(
    val latitude: Double,
    val longitude: Double
) : Parcelable {
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
