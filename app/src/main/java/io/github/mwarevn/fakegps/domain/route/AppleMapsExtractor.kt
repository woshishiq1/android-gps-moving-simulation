package io.github.mwarevn.fakegps.domain.route

import android.net.Uri
import android.util.Log
import io.github.mwarevn.fakegps.domain.model.LatLng

/**
 * Apple Maps route extractor
 * Supports:
 * - ll parameter: ll=lat,lon
 * - address parameter with location
 */
class AppleMapsExtractor : RouteExtractor {
    
    companion object {
        private const val TAG = "AppleMapsExtractor"
    }
    
    override fun canHandle(url: String): Boolean {
        return url.contains("apple.com/maps") || url.contains("maps.apple.com")
    }
    
    override suspend fun extract(url: String): RouteData? {
        Log.d(TAG, "▶▶ Extracting Apple Maps coords from: $url")
        
        try {
            val llParam = extractQueryParam(url, "ll")
            Log.d(TAG, "  ll='$llParam'")
            
            if (llParam != null) {
                val coords = parseCoordinates(llParam)
                if (coords != null) {
                    Log.d(TAG, "✓ Found Apple Maps coords: ${coords.first},${coords.second}")
                    return RouteData(
                        endPoint = LatLng(coords.first, coords.second),
                        appName = "Apple Maps"
                    )
                }
            }
            
            Log.d(TAG, "⚠ Could not extract Apple Maps coords (ll parameter not found or invalid)")
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Apple Maps URL", e)
        }
        
        return null
    }
    
    private fun extractQueryParam(url: String, paramName: String): String? {
        return try {
            val uri = Uri.parse(url)
            uri.getQueryParameter(paramName)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting query param: $paramName", e)
            null
        }
    }
    
    private fun parseCoordinates(coordString: String): Pair<Double, Double>? {
        return try {
            val parts = coordString.split(",")
            if (parts.size >= 2) {
                val lat = parts[0].toDoubleOrNull()
                val lon = parts[1].toDoubleOrNull()
                if (lat != null && lon != null) {
                    Pair(lat, lon)
                } else null
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing coordinates: $coordString", e)
            null
        }
    }
}
