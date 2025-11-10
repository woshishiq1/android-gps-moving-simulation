package io.github.mwarevn.movingsimulation.network

import android.util.Log
import com.google.android.gms.maps.model.LatLng
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
 *
 * Strategy:
 * 1. If MapBox API key exists → Try MapBox first (accurate, traffic-aware, motorcycle mode)
 * 2. If MapBox fails (quota exceeded, error, timeout) → Fallback to OSRM
 * 3. If OSRM primary (no key) fails → Try MapBox free as backup if key available
 *
 * This ensures maximum reliability and best routing quality.
 */
class RoutingService(
    private val mapBoxService: MapBoxDirectionsService?,
    private val osrmService: OsrmService,
    private val mapBoxApiKey: String?
) {
    companion object {
        private const val TAG = "RoutingService"
        private const val TIMEOUT_MS = 8000L // 8s timeout per request

        // Error codes from MapBox
        private const val MAPBOX_QUOTA_EXCEEDED = "RateLimitError"
        private const val MAPBOX_INVALID_TOKEN = "InvalidToken"
        private const val MAPBOX_NO_ROUTE = "NoRoute"
    }

    /**
     * Get route with intelligent hybrid fallback
     *
     * @param startLat Starting latitude
     * @param startLng Starting longitude
     * @param endLat Destination latitude
     * @param endLng Destination longitude
     * @param vehicleType Type of vehicle (CAR, MOTORBIKE, BICYCLE)
     * @return RoutingResult with routes and service info, or null if all services fail
     */
    suspend fun getRoute(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        vehicleType: VehicleType = VehicleType.MOTORBIKE
    ): RoutingResult? {

        val hasMapBoxKey = !mapBoxApiKey.isNullOrEmpty()

        // Strategy 1: MapBox primary (if key available)
        if (hasMapBoxKey && mapBoxService != null) {
            val mapBoxResult = tryGetRouteFromMapBox(
                startLat, startLng, endLat, endLng, vehicleType
            )

            if (mapBoxResult != null) {
                Log.d(TAG, "✅ Using MapBox (${vehicleType.mapBoxProfile})")
                return RoutingResult(
                    routes = mapBoxResult,
                    serviceName = "MapBox",
                    isFallback = false
                )
            }
            
            // MapBox failed, fallback to OSRM
            Log.w(TAG, "⚠️ MapBox failed, trying OSRM fallback...")
            val osrmResult = tryGetRouteFromOsrm(
                startLat, startLng, endLat, endLng, vehicleType
            )
            
            if (osrmResult != null) {
                Log.d(TAG, "✅ Using OSRM fallback (${vehicleType.osrmProfile})")
                return RoutingResult(
                    routes = osrmResult,
                    serviceName = "OSRM",
                    isFallback = true
                )
            }
            
            Log.e(TAG, "❌ Both MapBox and OSRM failed")
            return null
        }
        
        // Strategy 2: OSRM primary (no MapBox key)
        val osrmResult = tryGetRouteFromOsrm(
            startLat, startLng, endLat, endLng, vehicleType
        )
        
        if (osrmResult != null) {
            Log.d(TAG, "✅ Using OSRM (${vehicleType.osrmProfile})")
            return RoutingResult(
                routes = osrmResult,
                serviceName = "OSRM",
                isFallback = false
            )
        }
        
        // OSRM failed, try MapBox as backup if service exists (even without proper key)
        if (mapBoxService != null && hasMapBoxKey) {
            Log.w(TAG, "⚠️ OSRM failed, trying MapBox as backup...")
            val mapBoxResult = tryGetRouteFromMapBox(
                startLat, startLng, endLat, endLng, vehicleType
            )
            
            if (mapBoxResult != null) {
                Log.d(TAG, "✅ Using MapBox backup (${vehicleType.mapBoxProfile})")
                return RoutingResult(
                    routes = mapBoxResult,
                    serviceName = "MapBox",
                    isFallback = true
                )
            }
        }

        Log.e(TAG, "❌ All routing services failed")
        return null
    }

    /**
     * Try to get route from MapBox
     * Returns null if fails (quota exceeded, error, timeout, etc.)
     */
    private suspend fun tryGetRouteFromMapBox(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        vehicleType: VehicleType
    ): List<List<LatLng>>? {
        if (mapBoxService == null || mapBoxApiKey.isNullOrEmpty()) {
            return null
        }

        return try {
            val startTime = System.currentTimeMillis()

            val coordinates = "$startLng,$startLat;$endLng,$endLat"
            
            // Log the exact request being made
            Log.d(TAG, "MapBox request:")
            Log.d(TAG, "  Profile: ${vehicleType.mapBoxProfile}")
            Log.d(TAG, "  Coordinates: $coordinates")
            Log.d(TAG, "  URL: https://api.mapbox.com/directions/v5/mapbox/${vehicleType.mapBoxProfile}/$coordinates")

            val response = withTimeout(TIMEOUT_MS) {
                mapBoxService.getRoute(
                    profile = vehicleType.mapBoxProfile,
                    coordinates = coordinates,
                    alternatives = true, // Get up to 3 alternatives
                    accessToken = mapBoxApiKey
                )
            }

            val elapsed = System.currentTimeMillis() - startTime

            when (response.code) {
                "Ok" -> {
                    if (response.routes.isNullOrEmpty()) {
                        Log.w(TAG, "MapBox returned OK but no routes")
                        return null
                    }

                    Log.d(TAG, "MapBox success - ${elapsed}ms - ${response.routes.size} route(s)")

                    response.routes.mapNotNull { route ->
                        route.geometry?.let {
                            val points = decodePolyline(it, 1e6)
                            Log.d(TAG, "  Route: ${route.distance?.toInt()}m, ${route.duration?.toInt()}s, ${points.size} points")
                            points
                        }
                    }
                }

                MAPBOX_NO_ROUTE -> {
                    Log.w(TAG, "MapBox: No route found between coordinates")
                    null
                }

                MAPBOX_QUOTA_EXCEEDED -> {
                    Log.w(TAG, "MapBox: Quota exceeded (100k requests/month limit)")
                    null
                }

                MAPBOX_INVALID_TOKEN -> {
                    Log.e(TAG, "MapBox: Invalid API token")
                    null
                }

                else -> {
                    Log.e(TAG, "MapBox error: ${response.code} - ${response.message}")
                    Log.e(TAG, "Response: $response")
                    null
                }
            }
        } catch (e: retrofit2.HttpException) {
            // HTTP error (404, 401, 500, etc.)
            val code = e.code()
            val errorBody = e.response()?.errorBody()?.string()
            Log.e(TAG, "MapBox HTTP $code error:")
            Log.e(TAG, "  Error body: $errorBody")
            null
        } catch (e: IOException) {
            Log.w(TAG, "MapBox network error: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "MapBox error: ${e.javaClass.simpleName} - ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Try to get route from OSRM
     * Returns null if fails (timeout, error, etc.)
     */
    private suspend fun tryGetRouteFromOsrm(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        vehicleType: VehicleType
    ): List<List<LatLng>>? {
        return try {
            val startTime = System.currentTimeMillis()

            val coords = "$startLng,$startLat;$endLng,$endLat"

            val response = withTimeout(TIMEOUT_MS) {
                osrmService.getRoute(coords, profile = vehicleType.osrmProfile)
            }

            val elapsed = System.currentTimeMillis() - startTime

            if (response.routes.isNullOrEmpty()) {
                Log.w(TAG, "OSRM returned no routes")
                return null
            }

            Log.d(TAG, "OSRM success - ${elapsed}ms - ${response.routes.size} route(s)")

            response.routes.mapNotNull { route ->
                route.geometry?.let {
                    val points = decodePolyline(it, 1e6)
                    Log.d(TAG, "  Route: ${points.size} points")
                    points
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "OSRM network error: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "OSRM error: ${e.javaClass.simpleName} - ${e.message}")
            null
        }
    }

    /**
     * Decode polyline encoded string to list of LatLng
     * Both MapBox and OSRM use polyline6 encoding (precision = 1e6)
     *
     * @param encoded Polyline encoded string
     * @param precision Encoding precision (1e5 for Google, 1e6 for MapBox/OSRM)
     */
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
