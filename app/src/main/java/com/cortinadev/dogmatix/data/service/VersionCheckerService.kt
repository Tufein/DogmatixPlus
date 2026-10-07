package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.R
import android.content.Context
import android.content.pm.PackageManager
import com.cortinadev.dogmatix.BuildConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.cortinadev.dogmatix.data.model.GitHubRelease
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import com.cortinadev.dogmatix.util.ToastUtil
import com.cortinadev.dogmatix.util.ReleaseUpdates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VersionCheckerService @Inject constructor(
    private val gson: Gson,
    private val settingsRepository: SettingsRepository
) {

    /** What a check found. The tag identifies the newest release the update channel offers. */
    sealed interface Result {
        data class Available(val tag: String, val preRelease: Boolean) : Result
        data class UpToDate(val tag: String) : Result
        data object Failed : Result
    }
    
    companion object {
        private const val GITHUB_API_URL = "https://api.github.com/repos/Tufein/DogmatixPlus/releases?per_page=100"
        private const val REQUEST_TIMEOUT = 10000 // 10 seconds
    }
    
    suspend fun checkForUpdates(context: Context) {
        val result = check(context)
        if (result is Result.Available) {
            withContext(Dispatchers.Main) {
                ToastUtil.showInfo(context, context.getString(R.string.update_available, result.tag))
            }
        }
    }

    /**
     * Looks at the releases of this repository: the newest full release, or the newest of any kind
     * when Settings → Updates includes pre-releases. Published Android build numbers take priority
     * over labels, so renumbering a release cannot hide an update or offer an older APK.
     */
    suspend fun check(context: Context): Result {
        return try {
            val currentVersion = getCurrentVersion(context)
            val release = fetchLatestRelease(settingsRepository.updatePreReleases.first()) ?: return Result.Failed
            if (ReleaseUpdates.isNewer(release, currentVersion, BuildConfig.VERSION_CODE.toLong())) Result.Available(release.tagName, release.prerelease)
            else Result.UpToDate(release.tagName)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.Failed
        }
    }
    
    private fun getCurrentVersion(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName?.takeIf { it.isNotEmpty() } ?: "1.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "1.0" 
        }
    }
    
    private suspend fun fetchLatestRelease(includePreReleases: Boolean): GitHubRelease? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(GITHUB_API_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = REQUEST_TIMEOUT
                connection.readTimeout = REQUEST_TIMEOUT
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.setRequestProperty("User-Agent", "DogmatixPlus/${BuildConfig.VERSION_NAME}")

                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val releases = gson.fromJson<List<GitHubRelease>>(
                        response, 
                        object : TypeToken<List<GitHubRelease>>() {}.type
                    )
                    
                    ReleaseUpdates.latest(releases, includePreReleases)
                } finally {
                    connection.disconnect()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }
    
}
