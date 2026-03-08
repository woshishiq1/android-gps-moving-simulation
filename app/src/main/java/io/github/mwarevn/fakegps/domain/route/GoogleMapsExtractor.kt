package io.github.mwarevn.fakegps.domain.route

import android.net.Uri
import android.util.Log
import io.github.mwarevn.fakegps.domain.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google Maps route extractor
 * Supports:
 * - Direct URLs: https://maps.google.com/maps?ll=lat,lon
 * - Short URLs: https://goo.gl/maps/xxxxx, https://maps.app.goo.gl
 * - Direction URLs: /dir/lat,lon/lat,lon
 * - @notation: @lat,lon
 * - Center parameter: center=lat,lon
 * - Coordinate notation: !3d=lat!4d=lon
 */
class GoogleMapsExtractor : RouteExtractor {
    
    companion object {
        private const val TAG = "GoogleMapsExtractor"
    }
    
    override fun canHandle(url: String): Boolean {
        return url.contains("google.com/maps") || 
               url.contains("goo.gl") || 
               url.contains("maps.app.goo.gl")
    }
    
    override suspend fun extract(url: String): RouteData? {
        Log.d(TAG, "▶▶ Extracting coords from Google URL: $url")
        
        var urlToProcess = url
        
        // If it's a short URL, resolve it first
        if (url.contains("goo.gl") || url.contains("maps.app.goo.gl")) {
            val resolvedUrl = resolveShortUrl(url)
            if (resolvedUrl != null) {
                Log.d(TAG, "✓ Successfully resolved short URL")
                urlToProcess = resolvedUrl
            } else {
                Log.d(TAG, "✗ Failed to resolve short URL")
                return null
            }
        }
        
        // Try each pattern
        // Pattern 1: /dir/lat,lon/lat,lon (directions)
        val dirPattern = Regex("""/dir/([-\d.]+),([-\d.]+)/([-\d.]+),([-\d.]+)""")
        dirPattern.find(urlToProcess)?.let { match ->
            try {
                val sLat = match.groupValues[1].toDoubleOrNull()
                val sLon = match.groupValues[2].toDoubleOrNull()
                val dLat = match.groupValues[3].toDoubleOrNull()
                val dLon = match.groupValues[4].toDoubleOrNull()
                if (sLat != null && sLon != null && dLat != null && dLon != null) {
                    Log.d(TAG, "✓ Found /dir pattern: start($sLat,$sLon) -> dest($dLat,$dLon)")
                    return RouteData(
                        startPoint = LatLng(sLat, sLon),
                        endPoint = LatLng(dLat, dLon),
                        appName = "Google Maps"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing /dir pattern", e)
            }
        }
        
        // Pattern 2: @lat,lon (standard Google Maps format)
        val atPattern = Regex("""@([-\d.]+),([-\d.]+)""")
        atPattern.find(urlToProcess)?.let { match ->
            try {
                val lat = match.groupValues[1].toDoubleOrNull()
                val lon = match.groupValues[2].toDoubleOrNull()
                if (lat != null && lon != null) {
                    Log.d(TAG, "✓ Found @ pattern: $lat,$lon")
                    return RouteData(
                        endPoint = LatLng(lat, lon),
                        appName = "Google Maps"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing @ pattern", e)
            }
        }
        
        // Pattern 3: center=lat,lon query parameter
        val centerPattern = Regex("""center=([-\d.]+),([-\d.]+)""")
        centerPattern.find(urlToProcess)?.let { match ->
            try {
                val lat = match.groupValues[1].toDoubleOrNull()
                val lon = match.groupValues[2].toDoubleOrNull()
                if (lat != null && lon != null) {
                    Log.d(TAG, "✓ Found center pattern: $lat,$lon")
                    return RouteData(
                        endPoint = LatLng(lat, lon),
                        appName = "Google Maps"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing center pattern", e)
            }
        }
        
        // Pattern 4: !3d (latitude) and !4d (longitude)
        val latPattern = Regex("""!3d([-\d.]+)""")
        val lonPattern = Regex("""!4d([-\d.]+)""")
        latPattern.find(urlToProcess)?.let { latMatch ->
            lonPattern.find(urlToProcess)?.let { lonMatch ->
                try {
                    val lat = latMatch.groupValues[1].toDoubleOrNull()
                    val lon = lonMatch.groupValues[1].toDoubleOrNull()
                    if (lat != null && lon != null) {
                        Log.d(TAG, "✓ Found !3d/!4d pattern: $lat,$lon")
                        return RouteData(
                            endPoint = LatLng(lat, lon),
                            appName = "Google Maps"
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing !3d/!4d pattern", e)
                }
            }
        }
        
        Log.d(TAG, "⚠ Could not extract coordinates from Google URL")
        Log.d(TAG, "  URL content (first 200 chars): ${urlToProcess.take(200)}")
        return null
    }
    
    private suspend fun resolveShortUrl(shortUrl: String): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "▶▶▶ Resolving short URL: $shortUrl")
        
        var currentUrl = shortUrl
        var redirectCount = 0
        val maxRedirects = 5
        
        try {
            while (redirectCount < maxRedirects) {
                Log.d(TAG, "  [Attempt ${redirectCount + 1}] Opening connection to: $currentUrl")
                
                val connection = URL(currentUrl).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36")
                
                Log.d(TAG, "  Status code: ${connection.responseCode}")
                
                when (connection.responseCode) {
                    in 200..299 -> {
                        val finalUrl = connection.url.toString()
                        Log.d(TAG, "✓ Resolved to: $finalUrl")
                        connection.disconnect()
                        return@withContext finalUrl
                    }
                    in 300..399 -> {
                        val location = connection.getHeaderField("Location")
                        Log.d(TAG, "  Redirect [${connection.responseCode}] → Location header: $location")
                        connection.disconnect()
                        
                        if (location != null && location.isNotEmpty()) {
                            currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                                location
                            } else {
                                val baseUrl = URL(currentUrl)
                                URL(baseUrl, location).toString()
                            }
                            redirectCount++
                            Log.d(TAG, "  Following redirect to: $currentUrl")
                        } else {
                            Log.d(TAG, "✗ Redirect but no Location header")
                            return@withContext null
                        }
                    }
                    else -> {
                        Log.d(TAG, "✗ HTTP error: ${connection.responseCode}")
                        connection.disconnect()
                        return@withContext null
                    }
                }
            }
            
            Log.d(TAG, "✗ Too many redirects (max: $maxRedirects)")
            return@withContext null
            
        } catch (e: Exception) {
            Log.e(TAG, "✗ Exception resolving short URL: ${e.message}", e)
            return@withContext null
        }
    }
}
