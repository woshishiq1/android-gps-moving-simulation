package io.github.mwarevn.fakegps.utils.ext

import android.content.Context
import android.location.Geocoder
import io.github.mwarevn.fakegps.domain.model.LatLng
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.util.*

suspend fun LatLng.getAddress(context: Context) = callbackFlow {
    val geocoder = Geocoder(context, Locale.getDefault())
    try {
        val addresses = geocoder.getFromLocation(latitude, longitude, 1)
        val sb = StringBuilder()
        if (!addresses.isNullOrEmpty()) {
            val address = addresses[0]
            val addressStr = address.getAddressLine(0)
            
            // Format Address like existing behavior
            val strs = addressStr.split(",".toRegex()).toTypedArray()
            if (strs.size > 1) {
                sb.append(strs[0])
                val index = addressStr.indexOf(",") + 2
                if (index > 1 && addressStr.length > index) {
                    sb.append("\n").append(addressStr.substring(index))
                }
            } else {
                sb.append(addressStr)
            }
        } else {
            sb.append("Tọa độ: $latitude, $longitude")
        }
        trySend(sb.toString())
    } catch (e: Exception) {
        trySend("Tọa độ: $latitude, $longitude") // fallback
    }
    awaitClose { }
}