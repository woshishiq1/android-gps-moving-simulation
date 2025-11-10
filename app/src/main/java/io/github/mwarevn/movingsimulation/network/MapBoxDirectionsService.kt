package io.github.mwarevn.movingsimulation.network

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * MapBox Directions API Response
 *
 * FREE: 100,000 requests/month (forever)
 * Speed: ~200-400ms
 * Accuracy: High (updated weekly from OSM + proprietary data)
 *
 * Get API key at: https://account.mapbox.com/
 */
data class MapBoxDirectionsResponse(
    val routes: List<MapBoxRoute>?,
    val code: String, // "Ok", "NoRoute", "TooManyCoordinates", etc.
    val message: String? = null
)

data class MapBoxRoute(
    val geometry: String?, // polyline6 encoded
    val duration: Double?, // seconds
    val distance: Double?, // meters
    val weight: Double?, // routing weight for optimization
    val weight_name: String? = null,
    val legs: List<MapBoxLeg>? = null
)

data class MapBoxLeg(
    val duration: Double?,
    val distance: Double?,
    val summary: String? = null
)

/**
 * MapBox Directions API Service
 *
 * Profiles available:
 * - driving-traffic: Car with real-time traffic (also used for motorcycles)
 * - driving: Car without traffic
 * - cycling: Bicycle
 * - walking: Walking
 * 
 * Note: motorcycle profile is NOT available in Directions API
 *
 * Documentation: https://docs.mapbox.com/api/navigation/directions/
 */
interface MapBoxDirectionsService {

    @GET("directions/v5/mapbox/{profile}/{coordinates}")
    suspend fun getRoute(
        @Path("profile") profile: String,
        @Path(value = "coordinates", encoded = true) coordinates: String, // "lng,lat;lng,lat"
        @Query("alternatives") alternatives: Boolean = true, // Get up to 3 alternative routes
        @Query("geometries") geometries: String = "polyline6", // Use polyline6 encoding (same as OSRM)
        @Query("overview") overview: String = "full", // Full geometry
        @Query("steps") steps: Boolean = false, // Don't need turn-by-turn steps for GPS simulation
        @Query("continue_straight") continueStraight: Boolean = false, // Allow turns
        @Query("access_token") accessToken: String
    ): MapBoxDirectionsResponse
}
