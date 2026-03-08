package io.github.mwarevn.fakegps.domain.route

import android.net.Uri
import android.util.Log
import io.github.mwarevn.fakegps.domain.model.LatLng

/**
 * Grab route extractor
 * Supports:
 * - Share URLs: https://grab.com/share/directions/...
 * - from/to parameters: from=lat,lon&to=lat,lon
 * - origin/dest parameters: origin=lat,lon&dest=lat,lon
 */
class GrabExtractor : RouteExtractor {
    
    companion object {
        private const val TAG = "GrabExtractor"
    }
    
    override fun canHandle(url: String): Boolean {
        return url.contains("grab.com")
    }
    
    override suspend fun extract(url: String): RouteData? {
        Log.d(TAG, "▶▶ Extracting Grab coords from: $url")
        
        try {
            // Try from/to parameters first
            val from = extractQueryParam(url, "from")
            val to = extractQueryParam(url, "to")
            Log.d(TAG, "  from='$from', to='$to'")
            
            if (from != null && to != null) {
                val fromCoords = parseCoordinates(from)
                val toCoords = parseCoordinates(to)
                
                if (fromCoords != null && toCoords != null) {
                    Log.d(TAG, "✓ Found Grab coords (from/to): from(${fromCoords.first},${fromCoords.second}) to(${toCoords.first},${toCoords.second})")
                    return RouteData(
                        startPoint = LatLng(fromCoords.first, fromCoords.second),
                        endPoint = LatLng(toCoords.first, toCoords.second),
                        appName = "Grab"
                    )
                }
            }
            
            // Try origin/dest parameters
            val origin = extractQueryParam(url, "origin")
            val dest = extractQueryParam(url, "dest")
            Log.d(TAG, "  origin='$origin', dest='$dest'")
            
            if (origin != null && dest != null) {
                val originCoords = parseCoordinates(origin)
                val destCoords = parseCoordinates(dest)
                
                if (originCoords != null && destCoords != null) {
                    Log.d(TAG, "✓ Found Grab coords (origin/dest): origin(${originCoords.first},${originCoords.second}) dest(${destCoords.first},${destCoords.second})")
                    return RouteData(
                        startPoint = LatLng(originCoords.first, originCoords.second),
                        endPoint = LatLng(destCoords.first, destCoords.second),
                        appName = "Grab"
                    )
                }
            }
            
            Log.d(TAG, "⚠ Could not extract Grab coords (from/to or origin/dest not found)")
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Grab URL", e)
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
