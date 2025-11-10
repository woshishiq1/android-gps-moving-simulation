package io.github.mwarevn.movingsimulation.network

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

data class OsrmRouteResponse(val routes: List<OsrmRoute>?)
data class OsrmRoute(
    val geometry: String?,
    val duration: Double? = null,
    val distance: Double? = null
)

interface OsrmService {
    /**
     * OSRM Routing API
     * 
     * FREE: Unlimited requests
     * Speed: ~100-200ms (fastest)
     * 
     * Profiles:
     * - driving: Car
     * - bike: Bicycle/Motorcycle (workaround for motorbike)
     * - foot: Walking
     * 
     * Documentation: http://project-osrm.org/docs/v5.24.0/api/
     */
    @GET("route/v1/{profile}/{coords}")
    suspend fun getRoute(
        @Path(value = "coords", encoded = true) coords: String,
        @Path("profile") profile: String = "driving",
        @Query("overview") overview: String = "full",
        @Query("geometries") geometries: String = "polyline6",
        @Query("alternatives") alternatives: Boolean = false // OSRM free tier has limited alternatives
    ): OsrmRouteResponse
}
