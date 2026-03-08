package io.github.mwarevn.fakegps.domain.usecase

import io.github.mwarevn.fakegps.domain.model.LatLng
import io.github.mwarevn.fakegps.domain.model.VehicleType
import io.github.mwarevn.fakegps.domain.routing.IRoutingProvider
import javax.inject.Inject

/**
 * Use case for calculating a route between two points.
 */
class CalculateRouteUseCase @Inject constructor(
    private val routingProvider: IRoutingProvider
) {
    suspend operator fun invoke(
        start: LatLng,
        destination: LatLng,
        vehicleType: VehicleType
    ): Result<List<LatLng>> {
        return try {
            val route = routingProvider.getRoute(start, destination, vehicleType)
            if (route.isNotEmpty()) {
                Result.success(route)
            } else {
                Result.failure(Exception("No route found"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
