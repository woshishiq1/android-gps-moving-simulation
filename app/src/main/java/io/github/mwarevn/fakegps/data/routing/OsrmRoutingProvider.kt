package io.github.mwarevn.fakegps.data.routing

import io.github.mwarevn.fakegps.domain.model.LatLng
import io.github.mwarevn.fakegps.domain.model.VehicleType
import io.github.mwarevn.fakegps.domain.routing.IRoutingProvider
import io.github.mwarevn.fakegps.network.RoutingService
import io.github.mwarevn.fakegps.network.VehicleType as NetworkVehicleType
import io.github.mwarevn.fakegps.network.OsrmClient
import javax.inject.Inject

class OsrmRoutingProvider @Inject constructor(
    private val routingService: RoutingService
) : IRoutingProvider {
    
    override suspend fun getRoute(
        start: LatLng,
        destination: LatLng,
        vehicleType: VehicleType
    ): List<LatLng> {
        val networkVehicle = when (vehicleType) {
            VehicleType.CAR -> NetworkVehicleType.CAR
            VehicleType.MOTORBIKE -> NetworkVehicleType.MOTORBIKE
            VehicleType.BICYCLE -> NetworkVehicleType.BICYCLE
        }
        
        return try {
            val response = routingService.getRoute(
                start.latitude, start.longitude,
                destination.latitude, destination.longitude,
                networkVehicle
            )
            response?.routes?.getOrNull(0) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
