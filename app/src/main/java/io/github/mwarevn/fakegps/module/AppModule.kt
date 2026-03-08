package io.github.mwarevn.fakegps.module

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.mwarevn.fakegps.module.util.ApplicationScope
import io.github.mwarevn.fakegps.data.repository.FavoriteRepositoryImpl
import io.github.mwarevn.fakegps.data.routing.OsrmRoutingProvider
import io.github.mwarevn.fakegps.domain.repository.IFavoriteRepository
import io.github.mwarevn.fakegps.domain.routing.IRoutingProvider
import io.github.mwarevn.fakegps.network.OsrmClient
import io.github.mwarevn.fakegps.network.RoutingService
import io.github.mwarevn.fakegps.network.StatusService
import io.github.mwarevn.fakegps.room.AppDatabase
import io.github.mwarevn.fakegps.room.FavoriteDao
import io.github.mwarevn.fakegps.update.GitHubService
import io.github.mwarevn.fakegps.utils.PrefManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Singleton
    @Provides
    fun provideRetrofit(): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.github.com/repos/mwarevn/fake-gps/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Singleton
    @Provides
    fun provideGithubService(retrofit: Retrofit): GitHubService =
        retrofit.create(GitHubService::class.java)

    @Singleton
    @Provides
    fun provideStatusService(): StatusService =
        Retrofit.Builder()
            .baseUrl("https://6514b3f1dc3282a6a3cd7125.mockapi.io/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(StatusService::class.java)

    @Singleton
    @Provides
    fun provideRoutingService(): RoutingService = OsrmClient.createRoutingService("invalid_token_not_needed_for_osrm")

    @Singleton
    @Provides
    fun provideRoutingProvider(routingService: RoutingService): IRoutingProvider =
        OsrmRoutingProvider(routingService)

    @Singleton
    @Provides
    fun provideDownloadManger(application: Application) =
        application.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    @Provides
    @Singleton
    fun provideDatabase(application: Application, callback: AppDatabase.Callback) =
        Room.databaseBuilder(application, AppDatabase::class.java, "user_database")
            .allowMainThreadQueries()
            .fallbackToDestructiveMigration()
            .addCallback(callback)
            .build()

    @Singleton
    @Provides
    fun providesUserDao(favoriteDatabase: AppDatabase): FavoriteDao =
        favoriteDatabase.favoriteDao()

    @Singleton
    @Provides
    fun provideFavoriteRepository(favoriteDao: FavoriteDao): IFavoriteRepository =
        FavoriteRepositoryImpl(favoriteDao)

    @Singleton
    @Provides
    fun provideSettingRepo(): PrefManager = PrefManager

    @ApplicationScope
    @Provides
    @Singleton
    fun providesApplicationScope() = CoroutineScope(SupervisorJob())
}
