package io.github.mwarevn.movingsimulation.network

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

data class OsrmRouteResponse(val routes: List<OsrmRoute>?)
data class OsrmRoute(val geometry: String?)

interface OsrmService {
    @GET("route/v1/driving/{coords}")
    suspend fun getRoute(
        @Path(value = "coords", encoded = true) coords: String,
        @Query("overview") overview: String = "full",
        @Query("geometries") geometries: String = "polyline6"
    ): OsrmRouteResponse
}
