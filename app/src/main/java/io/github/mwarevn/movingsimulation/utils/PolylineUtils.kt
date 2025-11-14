package io.github.mwarevn.movingsimulation.utils

import com.google.android.gms.maps.model.LatLng
import kotlin.math.*

object PolylineUtils {
    // Decode an encoded polyline string (precision 1e5 or 1e6 depending on factor)
    fun decode(encoded: String, precision: Int = 6): List<LatLng> {
        val coords = ArrayList<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if ((result and 1) != 0) (result.inv() shr 1) else (result shr 1)
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if ((result and 1) != 0) (result.inv() shr 1) else (result shr 1)
            lng += dlng

            val factor = 10.0.pow(precision)
            coords.add(LatLng(lat / factor, lng / factor))
        }
        return coords
    }

    fun haversineDistanceMeters(a: LatLng, b: LatLng): Double {
        val R = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)

        val sinDLat = sin(dLat / 2)
        val sinDLon = sin(dLon / 2)
        val aa = sinDLat * sinDLat + sinDLon * sinDLon * cos(lat1) * cos(lat2)
        val c = 2 * atan2(sqrt(aa), sqrt(1 - aa))
        return R * c
    }
}
