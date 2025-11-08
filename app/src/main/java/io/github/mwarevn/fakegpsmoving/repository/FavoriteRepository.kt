package io.github.mwarevn.fakegpsmoving.repository


import androidx.annotation.WorkerThread
import io.github.mwarevn.fakegpsmoving.room.Favorite
import io.github.mwarevn.fakegpsmoving.room.FavoriteDao
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class FavoriteRepository @Inject constructor(private val favoriteDao: FavoriteDao) {

        val getAllFavorites: Flow<List<Favorite>>
        get() =  favoriteDao.getAllFavorites()

        @Suppress("RedundantSuspendModifier")
        @WorkerThread
        suspend fun addNewFavorite(favorite: Favorite) : Long {
            return favoriteDao.insertToRoomDatabase(favorite)
        }

        suspend fun deleteFavorite(favorite: Favorite) {
          favoriteDao.deleteSingleFavorite(favorite)
       }


       fun getSingleFavorite(id: Long) : Favorite {
       return favoriteDao.getSingleFavorite(id)

    }

}