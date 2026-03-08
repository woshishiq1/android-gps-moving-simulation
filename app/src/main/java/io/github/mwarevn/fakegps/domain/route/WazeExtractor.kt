package io.github.mwarevn.fakegps.domain.route

import android.net.Uri
import android.util.Log
import io.github.mwarevn.fakegps.domain.model.LatLng

/**
 * Waze route extractor
 * Supports:
 * - ll parameter: ll=lat,lon
 * - navigate parameter to indicate navigation mode
 */
class WazeExtractor : RouteExtractor {
    
    companion object {
        private const val TAG = "WazeExtractor"
    }
    
    override fun canHandle(url: String): Boolean {
        return url.contains("waze.com")
    }
    
    override suspend fun extract(url: String): RouteData? {
        Log.d(TAG, "▶▶ Extracting Waze coords from: $url")
        
        try {
            val llParam = extractQueryParam(url, "ll")
            Log.d(TAG, "  ll='$llParam'")
            
            if (llParam != null) {
                val coords = parseCoordinates(llParam)
                if (coords != null) {
                    Log.d(TAG, "✓ Found Waze coords: ${coords.first},${coords.second}")
                    return RouteData(
                        endPoint = LatLng(coords.first, coords.second),
                        appName = "Waze"
                    )
                }
            }
            
            Log.d(TAG, "⚠ Could not extract Waze coords (ll parameter not found or invalid)")
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Waze URL", e)
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
