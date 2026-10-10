package com.cortinadev.dogmatix.data.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.cortinadev.dogmatix.BuildConfig
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.util.ToastUtil
import com.cortinadev.dogmatix.util.UpdateAssets
import com.cortinadev.dogmatix.util.UpdateInstallability
import com.cortinadev.dogmatix.util.ReleaseUpdates
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UpdateInstaller"
private const val RELEASES_API = "https://api.github.com/repos/Tufein/DogmatixPlus/releases/tags/"

/**
 * Installs a newer release from inside the app: downloads the APK of the GitHub release that
 * matches this build, checks it against the release's SHA256SUMS.txt and hands it to Android's
 * package installer, which asks the user to confirm. Android itself refuses an APK that is not
 * signed with the same key as the installed app.
 */
@Singleton
class UpdateInstaller @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val _progress = MutableStateFlow<Float?>(null)
    private val downloadLock = Mutex()
    /** 0..1 while the APK downloads, null otherwise. */
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    /** Returns null when the installer was started, else why not (shown to the user). */
    suspend fun downloadAndInstall(tag: String): String? = withContext(Dispatchers.IO) {
        if (!downloadLock.tryLock()) return@withContext context.getString(R.string.update_already_downloading)
        try {
            downloadAndInstallLocked(tag)
        } finally {
            downloadLock.unlock()
        }
    }

    private suspend fun downloadAndInstallLocked(tag: String): String? {
        if (!context.packageManager.canRequestPackageInstalls()) {
            // The user first allows "install unknown apps" for us; the next tap installs.
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return context.getString(R.string.update_allow_installs)
        }
        val apk = File(File(context.cacheDir, "updates").apply { mkdirs() }, "update.apk")
        try {
            val release = JsonParser.parseString(get(RELEASES_API + tag)).asJsonObject
            val assets = release.getAsJsonArray("assets").map { it.asJsonObject }
            fun urlOf(name: String) = assets.firstOrNull { it.get("name").asString == name }?.get("browser_download_url")?.asString
            val apkName = UpdateAssets.apkName(BuildConfig.DEBUG)
            val apkUrl = urlOf(apkName) ?: return context.getString(R.string.update_no_apk, apkName)
            val expected = urlOf(UpdateAssets.CHECKSUMS)?.let { UpdateAssets.checksumFor(get(it), apkName) }
                ?: return context.getString(R.string.update_no_checksum)
            val actual = download(apkUrl, apk)
            if (!actual.equals(expected, ignoreCase = true)) {
                apk.delete()
                return context.getString(R.string.update_checksum_mismatch)
            }
            val expectedVersionCode = ReleaseUpdates.versionCode(release.get("body")?.takeUnless { it.isJsonNull }?.asString)
            val preflight = preflight(apk, tag, expectedVersionCode)
            if (preflight != null) {
                apk.delete()
                return preflight
            }
            currentCoroutineContext().ensureActive()
            install(apk)
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Update $tag failed", e)
            apk.delete()
            return e.message ?: e.javaClass.simpleName
        } finally {
            apk.delete()
            _progress.value = null
        }
    }

    /** Check the package before opening PackageInstaller, so a stale/debug key is actionable. */
    internal fun preflight(apk: File, tag: String, expectedVersionCode: Long?): String? {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val candidate = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: return context.getString(R.string.update_invalid_apk)
        if (candidate.packageName != context.packageName) {
            return context.getString(R.string.update_wrong_package)
        }
        if (candidate.versionName?.removeSuffix("-debug") != tag.removePrefix("v") ||
            expectedVersionCode?.let { it != candidate.longVersionCode } == true) {
            return context.getString(R.string.update_invalid_apk)
        }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        if (candidate.longVersionCode <= installed.longVersionCode) {
            return context.getString(R.string.update_not_newer)
        }
        val installedSigners = installed.signingInfo?.apkContentsSigners.orEmpty().map { it.toByteArray() }
        val candidateSigners = candidate.signingInfo?.apkContentsSigners.orEmpty().map { it.toByteArray() }
        if (!UpdateInstallability.hasMatchingCertificate(installedSigners, candidateSigners)) {
            return context.getString(R.string.update_incompatible_signature)
        }
        return null
    }

    private fun get(url: String): String {
        val connection = open(url)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IllegalStateException("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Downloads [url] into [target] and returns its SHA-256. */
    private suspend fun download(url: String, target: File): String {
        val connection = open(url)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IllegalStateException("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            _progress.value = 0f
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (total > 0) _progress.value = done.toFloat() / total
                    }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream, */*")
        setRequestProperty("User-Agent", "DogmatixPlus/${BuildConfig.VERSION_NAME}")
    }

    private suspend fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            out.write(buffer, 0, count)
                        }
                    }
                    session.fsync(out)
                }
                currentCoroutineContext().ensureActive()
                val status = PendingIntent.getBroadcast(
                    context, id, Intent(context, UpdateInstallReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                session.commit(status.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }
}

/** Status of the install session: shows the system's confirmation, or says why it failed. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: status.toString()
                Log.w(TAG, "Install failed: $status $message")
                ToastUtil.showError(context, context.getString(R.string.update_install_failed, message))
            }
        }
    }
}
