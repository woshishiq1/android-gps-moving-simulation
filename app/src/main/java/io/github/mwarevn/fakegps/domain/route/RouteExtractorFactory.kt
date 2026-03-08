package io.github.mwarevn.fakegps.domain.route

import android.util.Log

/**
 * Factory class that detects the navigation app and returns the appropriate extractor
 * This allows for easy addition of new extractors without changing existing code (Open/Closed Principle)
 * 
 * To add support for a new app:
 * 1. Create a new class implementing RouteExtractor (e.g., class NewAppExtractor : RouteExtractor)
 * 2. Add it to the extractors list in init block
 * 3. Done! No other code changes needed
 */
class RouteExtractorFactory {
    
    companion object {
        private const val TAG = "RouteExtractorFactory"
        
        private val instance = RouteExtractorFactory()
        
        fun getInstance(): RouteExtractorFactory = instance
    }
    
    // List of all available extractors
    // Add new extractors here to support new apps
    private val extractors: List<RouteExtractor> = listOf(
        GoogleMapsExtractor(),
        GrabExtractor(),
        AppleMapsExtractor(),
        WazeExtractor()
        // Add more extractors here as needed:
        // YandexMapsExtractor(),
        // BaiduMapsExtractor(),
        // etc.
    )
    
    /**
     * Find the appropriate extractor for the given URL
     * @param url The URL to check
     * @return The first extractor that can handle this URL, or null if no extractor can handle it
     */
    fun getExtractor(url: String): RouteExtractor? {
        Log.d(TAG, "Finding extractor for URL: $url")
        
        for (extractor in extractors) {
            if (extractor.canHandle(url)) {
                val appName = when (extractor) {
                    is GoogleMapsExtractor -> "Google Maps"
                    is GrabExtractor -> "Grab"
                    is AppleMapsExtractor -> "Apple Maps"
                    is WazeExtractor -> "Waze"
                    else -> extractor::class.simpleName ?: "Unknown"
                }
                Log.d(TAG, "✓ Found handler for $appName")
                return extractor
            }
        }
        
        Log.d(TAG, "⚠ No handler found for URL")
        return null
    }
    
    /**
     * Get all registered extractors (useful for debugging or testing)
     */
    fun getAllExtractors(): List<RouteExtractor> = extractors
}
