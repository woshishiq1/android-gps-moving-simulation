package io.github.mwarevn.movingsimulation.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Retrofit Client for Routing Services
 * Supports both MapBox Directions API and OSRM
 */
object OsrmClient {
    private const val OSRM_BASE_URL = "https://router.project-osrm.org/"
    private const val MAPBOX_BASE_URL = "https://api.mapbox.com/"

    // Legacy OSRM service (for backward compatibility)
    val service: OsrmService by lazy {
        osrmService
    }
    
    // OSRM Retrofit instance
    private val osrmRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(OSRM_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
    
    // MapBox Retrofit instance
    private val mapBoxRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(MAPBOX_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
    
    // OSRM Service
    val osrmService: OsrmService by lazy {
        osrmRetrofit.create(OsrmService::class.java)
    }
    
    // MapBox Service
    val mapBoxService: MapBoxDirectionsService by lazy {
        mapBoxRetrofit.create(MapBoxDirectionsService::class.java)
    }
    
    /**
     * Create hybrid routing service
     * 
     * @param mapBoxApiKey Optional MapBox API key
     *                     - If provided: Use MapBox primary, OSRM fallback
     *                     - If null: Use OSRM primary, MapBox fallback (if key later available)
     *                     
     * Get free MapBox API key at: https://account.mapbox.com/
     * Free tier: 100,000 requests/month (forever)
     */
    fun createRoutingService(mapBoxApiKey: String? = null): RoutingService {
        return RoutingService(
            mapBoxService = if (mapBoxApiKey != null) mapBoxService else null,
            osrmService = osrmService,
            mapBoxApiKey = mapBoxApiKey
        )
    }
}
