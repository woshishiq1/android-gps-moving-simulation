package io.github.mwarevn.fakegps.domain.route

import io.github.mwarevn.fakegps.domain.model.LatLng

/**
 * Interface for extracting route coordinates from different navigation app URLs
 * Implement this interface for each new navigation app you want to support
 */
interface RouteExtractor {
    /**
     * Check if this extractor can handle the given URL
     * @param url The URL to check
     * @return true if this extractor can parse this URL
     */
    fun canHandle(url: String): Boolean
    
    /**
     * Extract route coordinates from URL
     * @param url The URL to parse
     * @return RouteData if successful, null if extraction failed
     */
    suspend fun extract(url: String): RouteData?
}

/**
 * Data class representing extracted route information
 */
data class RouteData(
    val startPoint: LatLng? = null,
    val endPoint: LatLng,
    val appName: String
)
