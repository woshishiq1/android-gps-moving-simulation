package io.github.mwarevn.fakegps.domain.routing

import io.github.mwarevn.fakegps.domain.model.LatLng
import io.github.mwarevn.fakegps.domain.model.VehicleType

/**
 * Interface for abstraction of Routing providers (OSRM, Mapbox, Google Directions, etc.)
 */
interface IRoutingProvider {
    suspend fun getRoute(
        start: LatLng,
        destination: LatLng,
        vehicleType: VehicleType
    ): List<LatLng>
}
