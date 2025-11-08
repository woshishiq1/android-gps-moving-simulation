package io.github.mwarevn.fakegpsmoving.utils.ext

import android.content.Context
import android.location.Geocoder
import io.github.mwarevn.fakegpsmoving.ui.CustomLatLng
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.util.*

suspend fun CustomLatLng.getAddress(context: Context) = callbackFlow {
    withContext(Dispatchers.IO){
        val addresses =
            Geocoder(context, Locale.getDefault()).getFromLocation(latitude, longitude, 1)
        val sb = StringBuilder()
        if (addresses!!.size > 0) {
            val address = addresses[0].getAddressLine(0)
            val strs = address.split(",".toRegex()).toTypedArray()
            if (strs.size > 1) {
                sb.append(strs[0])
                val index = address.indexOf(",") + 2
                if (index > 1 && address.length > index) {
                    sb.append("\n").append(address.substring(index))
                }
            } else {
                sb.append(address)
            }
        }
        trySend(sb.toString())
    }
    awaitClose { this.cancel() }
}