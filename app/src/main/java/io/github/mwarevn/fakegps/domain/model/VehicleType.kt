package io.github.mwarevn.fakegps.domain.model

/**
 * Domain entity representing vehicle types.
 */
enum class VehicleType(val displayName: String) {
    CAR("Ô tô"),
    MOTORBIKE("Xe máy"),
    BICYCLE("Xe đạp");
    
    companion object {
        fun fromString(value: String?): VehicleType {
            return when (value?.lowercase()) {
                "car", "oto", "ô tô" -> CAR
                "motorbike", "motorcycle", "xe may", "xe máy" -> MOTORBIKE
                "bicycle", "bike", "xe dap", "xe đạp" -> BICYCLE
                else -> MOTORBIKE
            }
        }
    }
}
