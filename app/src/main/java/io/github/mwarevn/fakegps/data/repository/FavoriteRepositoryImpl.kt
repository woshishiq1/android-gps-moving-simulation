package io.github.mwarevn.fakegps.data.repository

import io.github.mwarevn.fakegps.domain.model.FavoriteLocation
import io.github.mwarevn.fakegps.domain.repository.IFavoriteRepository
import io.github.mwarevn.fakegps.room.Favorite
import io.github.mwarevn.fakegps.room.FavoriteDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class FavoriteRepositoryImpl @Inject constructor(
    private val favoriteDao: FavoriteDao
) : IFavoriteRepository {
    
    override fun getAllFavorites(): Flow<List<FavoriteLocation>> {
        return favoriteDao.getAllFavorites().map { list ->
            list.map { it.toDomain() }
        }
    }

    override suspend fun addNewFavorite(favorite: FavoriteLocation): Long {
        return favoriteDao.insertToRoomDatabase(favorite.toData())
    }

    override suspend fun deleteFavorite(favorite: FavoriteLocation) {
        favoriteDao.deleteSingleFavorite(favorite.toData())
    }

    override suspend fun updateFavorite(favorite: FavoriteLocation) {
        favoriteDao.updateUserDetails(favorite.toData())
    }
    
    // Mappers
    private fun Favorite.toDomain() = FavoriteLocation(
        id = id,
        address = address ?: "",
        lat = lat ?: 0.0,
        lng = lng ?: 0.0
    )
    
    private fun FavoriteLocation.toData() = Favorite(
        id = id,
        address = address,
        lat = lat,
        lng = lng
    )
}
