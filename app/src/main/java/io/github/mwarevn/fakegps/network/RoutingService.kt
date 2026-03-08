package io.github.mwarevn.fakegps.network

import android.util.Log
import io.github.mwarevn.fakegps.utils.LatLng
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * Result of routing request with service info
 */
data class RoutingResult(
    val routes: List<List<LatLng>>,
    val serviceName: String, // "MapBox" or "OSRM"
    val isFallback: Boolean = false // true if using fallback service
)

/**
 * Hybrid Routing Service
 */
class RoutingService(
    private val mapBoxService: MapBoxDirectionsService?,
    private val osrmService: OsrmService,
    private val mapBoxApiKey: String?
) {
    companion object {
        private const val TAG = "RoutingService"
        private const val TIMEOUT_MS = 8000L // 8s timeout per request
    }

    suspend fun getRoute(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        vehicleType: VehicleType = VehicleType.MOTORBIKE
    ): RoutingResult? {

        val hasMapBoxKey = !mapBoxApiKey.isNullOrEmpty()

        // Strategy 1: MapBox primary
        if (hasMapBoxKey && mapBoxService != null) {
            val mapBoxResult = tryGetRouteFromMapBox(startLat, startLng, endLat, endLng, vehicleType)
            if (mapBoxResult != null) {
                return RoutingResult(routes = mapBoxResult, serviceName = "MapBox")
            }
        }
        
        // Strategy 2: OSRM
        val osrmResult = tryGetRouteFromOsrm(startLat, startLng, endLat, endLng, vehicleType)
        if (osrmResult != null) {
            return RoutingResult(routes = osrmResult, serviceName = "OSRM")
        }

        return null
    }

    private suspend fun tryGetRouteFromMapBox(
        startLat: Double, startLng: Double, endLat: Double, endLng: Double, vehicleType: VehicleType
    ): List<List<LatLng>>? {
        if (mapBoxService == null || mapBoxApiKey.isNullOrEmpty()) return null
        return try {
            val coords = "$startLng,$startLat;$endLng,$endLat"
            val response = withTimeout(TIMEOUT_MS) {
                mapBoxService.getRoute(
                    profile = vehicleType.mapBoxProfile,
                    coordinates = coords,
                    alternatives = true,
                    accessToken = mapBoxApiKey
                )
            }
            if (response.code == "Ok") {
                response.routes?.mapNotNull { route -> 
                    route.geometry?.let { g -> decodePolyline(g, 1e6) } 
                }
            } else null
        } catch (e: Exception) { null }
    }

    private suspend fun tryGetRouteFromOsrm(
        startLat: Double, startLng: Double, endLat: Double, endLng: Double, vehicleType: VehicleType
    ): List<List<LatLng>>? {
        return try {
            val coords = "$startLng,$startLat;$endLng,$endLat"
            // Use OSRM default profile "driving" if bike fails
            val response = withTimeout(TIMEOUT_MS) {
                osrmService.getRoute(coords, profile = "driving")
            }
            if (!response.routes.isNullOrEmpty()) {
                response.routes.mapNotNull { it.geometry?.let { g -> decodePolyline(g, 1e6) } }
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "OSRM error: ${e.message}")
            null
        }
    }

    private fun decodePolyline(encoded: String, precision: Double): List<LatLng> {
        val poly = mutableListOf<LatLng>()
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
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat
            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng
            poly.add(LatLng(lat / precision, lng / precision))
        }
        return poly
    }
}
