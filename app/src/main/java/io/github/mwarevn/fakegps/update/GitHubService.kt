package io.github.mwarevn.fakegps.update

import retrofit2.Call
import retrofit2.http.GET

interface GitHubService {
    // Đảm bảo URL này trỏ đúng vào repository của bạn trên GitHub
    @GET("https://github.com/mwarevn/fake-gps/releases/latest")
    fun getReleases(): Call<GitHubRelease>
}
