package io.github.mwarevn.fakegps.utils.ext

import android.content.Context
import android.location.Geocoder
import io.github.mwarevn.fakegps.domain.model.LatLng
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.*

/**
 * Get address from LatLng. Returns a Flow that emits the formatted address or a fallback.
 */
fun LatLng.getAddress(context: Context): Flow<String> = flow {
    val geocoder = Geocoder(context, Locale.getDefault())
    try {
        val addresses = geocoder.getFromLocation(latitude, longitude, 1)
        if (!addresses.isNullOrEmpty()) {
            val address = addresses[0]
            val addressStr = address.getAddressLine(0) ?: ""
            
            // Format Address: take first part and rest on new line
            val parts = addressStr.split(",")
            if (parts.size > 1) {
                val firstPart = parts[0].trim()
                val rest = addressStr.substring(addressStr.indexOf(",") + 1).trim()
                emit("$firstPart\n$rest")
            } else {
                emit(addressStr)
            }
        } else {
            emit(String.format("%.5f, %.5f", latitude, longitude))
        }
    } catch (e: Exception) {
        emit(String.format("%.5f, %.5f", latitude, longitude))
    }
}

/**
 * Blocking/Suspend version to get address string directly
 */
suspend fun LatLng.getAddressSync(context: Context): String = withContext(Dispatchers.IO) {
    val geocoder = Geocoder(context, Locale.getDefault())
    try {
        val addresses = geocoder.getFromLocation(latitude, longitude, 1)
        if (!addresses.isNullOrEmpty()) {
            val address = addresses[0]
            return@withContext address.getAddressLine(0) ?: String.format("%.5f, %.5f", latitude, longitude)
        }
    } catch (e: Exception) { }
    return@withContext String.format("%.5f, %.5f", latitude, longitude)
}
