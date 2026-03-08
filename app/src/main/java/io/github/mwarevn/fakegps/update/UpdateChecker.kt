package io.github.mwarevn.fakegps.update

import android.content.Context
import android.os.Parcelable
import io.github.mwarevn.fakegps.BuildConfig
import io.github.mwarevn.fakegps.utils.PrefManager
import kotlinx.parcelize.Parcelize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import timber.log.Timber

class UpdateChecker @Inject constructor(private val apiResponse : GitHubService) {

    fun getLatestRelease() = callbackFlow {
        withContext(Dispatchers.IO){
            val response = getReleaseList()
            if (response == null) {
                Timber.e("UpdateChecker: Failed to fetch release from GitHub")
                this@callbackFlow.trySend(null).isSuccess
                return@withContext
            }

            response.let { gitHubReleaseResponse ->
                val currentTag = gitHubReleaseResponse.tagName
                val latestVersion = currentTag?.replace("v", "")?.trim() ?: ""
                val currentVersion = BuildConfig.TAG_NAME.trim()

                Timber.d("UpdateChecker: Latest=$latestVersion, Current=$currentVersion, Disabled=${PrefManager.isUpdateDisabled}")

                // Kiểm tra nếu phiên bản khác nhau và tính năng update không bị tắt
                if (latestVersion.isNotEmpty() && latestVersion != currentVersion && !PrefManager.isUpdateDisabled) {
                    Timber.i("UpdateChecker: New update found!")
                    val asset = gitHubReleaseResponse.assets?.firstOrNull { it.name?.endsWith(".apk") == true }
                    // Sử dụng URL mặc định vì GitHubRelease không có htmlUrl
                    val releaseUrl = "https://github.com/mwarevn/fake-gps/releases/latest"
                    
                    val name = gitHubReleaseResponse.name ?: "Bản cập nhật mới"
                    val body = gitHubReleaseResponse.body ?: "Vui lòng cập nhật để tiếp tục sử dụng ứng dụng."
                    val publishedAt = gitHubReleaseResponse.publishedAt ?: ""
                    
                    this@callbackFlow.trySend(
                        Update(
                            name,
                            body,
                            publishedAt,
                            asset?.browserDownloadUrl ?: releaseUrl,
                            asset?.name ?: "app-arm64-v8a-release.apk",
                            releaseUrl
                        )
                    ).isSuccess
                } else {
                    this@callbackFlow.trySend(null).isSuccess
                }
            }
        }
        awaitClose { }
    }

    private fun getReleaseList(): GitHubRelease? {
        return runCatching {
            apiResponse.getReleases().execute().body()
        }.getOrNull()
    }

    fun clearCachedDownloads(context: Context){
        File(context.externalCacheDir, "updates").deleteRecursively()
    }

    @Parcelize
    data class Update(val name: String, val changelog: String, val timestamp: String, val assetUrl: String, val assetName: String, val releaseUrl: String):
        Parcelable
}
