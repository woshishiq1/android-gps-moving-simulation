package io.github.mwarevn.movingsimulation.network

/**
 * Vehicle types for routing
 * Maps to different profiles in MapBox and OSRM
 */
enum class VehicleType(
    val displayName: String,
    val mapBoxProfile: String,
    val osrmProfile: String
) {
    CAR(
        displayName = "Ô tô",
        mapBoxProfile = "driving-traffic", // Car with real-time traffic
        osrmProfile = "driving"
    ),
    
    MOTORBIKE(
        displayName = "Xe máy",
        mapBoxProfile = "driving-traffic", // Use driving-traffic (motorcycle profile not available in Directions API)
        osrmProfile = "bike" // OSRM doesn't have motorcycle, use bike as workaround
    ),
    
    BICYCLE(
        displayName = "Xe đạp",
        mapBoxProfile = "cycling",
        osrmProfile = "bike"
    );
    
    companion object {
        fun fromString(value: String?): VehicleType {
            return when (value?.lowercase()) {
                "car", "oto", "ô tô" -> CAR
                "motorbike", "motorcycle", "xe may", "xe máy" -> MOTORBIKE
                "bicycle", "bike", "xe dap", "xe đạp" -> BICYCLE
                else -> MOTORBIKE // Default to motorbike for VN
            }
        }
    }
}
