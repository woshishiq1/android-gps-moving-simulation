package io.github.mwarevn.fakegps.domain.model

/**
 * Domain entity representing a favorite location.
 */
data class FavoriteLocation(
    val id: Long?,
    val address: String,
    val lat: Double,
    val lng: Double
)
