package io.github.mwarevn.fakegps.domain.repository

import io.github.mwarevn.fakegps.domain.model.FavoriteLocation
import kotlinx.coroutines.flow.Flow

/**
 * Interface for abstraction of Favorite locations storage.
 */
interface IFavoriteRepository {
    fun getAllFavorites(): Flow<List<FavoriteLocation>>
    suspend fun addNewFavorite(favorite: FavoriteLocation): Long
    suspend fun deleteFavorite(favorite: FavoriteLocation)
    suspend fun updateFavorite(favorite: FavoriteLocation)
}
