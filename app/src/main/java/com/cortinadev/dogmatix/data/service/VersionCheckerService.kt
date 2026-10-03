package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.R
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.cortinadev.dogmatix.data.model.GitHubRelease
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import com.cortinadev.dogmatix.util.ToastUtil
import com.cortinadev.dogmatix.util.VersionUtils
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

    /** What a check found. [latest] is the newest tag the update channel offers. */
    sealed interface Result {
        data class Available(val tag: String, val preRelease: Boolean) : Result
        data class UpToDate(val tag: String) : Result
        data object Failed : Result
    }
    
    companion object {
        private const val GITHUB_API_URL = "https://api.github.com/repos/Tufein/DogmatixPlus/releases"
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
     * when Settings → Updates includes pre-releases. A pre-release of a version counts as older than
     * the version itself, so someone on 1.2.0-alpha.1 is told when 1.2.0 comes out.
     */
    suspend fun check(context: Context): Result {
        return try {
            val currentVersion = getCurrentVersion(context)
            val release = fetchLatestRelease(settingsRepository.updatePreReleases.first()) ?: return Result.Failed
            if (isNewerVersion(release.tagName, currentVersion)) Result.Available(release.tagName, release.prerelease)
            else Result.UpToDate(release.tagName)
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
                connection.setRequestProperty("User-Agent", "Milou-Android-App")
                
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val releases = gson.fromJson<List<GitHubRelease>>(
                        response, 
                        object : TypeToken<List<GitHubRelease>>() {}.type
                    )
                    
                    // The first release of the chosen channel: GitHub lists the newest first.
                    releases.firstOrNull { (includePreReleases || !it.prerelease) && !it.draft }
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
    
    private fun isNewerVersion(latestVersion: String, currentVersion: String): Boolean {
        return try {
            VersionUtils.compareVersions(latestVersion, currentVersion) > 0
        } catch (e: Exception) {
            false
        }
    }
}
