package io.github.mwarevn.fakegpsmoving.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object OsrmClient {
    private const val BASE_URL = "https://router.project-osrm.org/"

    val service: OsrmService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OsrmService::class.java)
    }
}
