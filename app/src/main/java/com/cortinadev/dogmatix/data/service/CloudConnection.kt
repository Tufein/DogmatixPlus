package com.cortinadev.dogmatix.data.service

import android.util.Log
import com.cortinadev.dogmatix.data.local.CloudConfig
import com.cortinadev.dogmatix.data.local.CloudSettings
import com.cortinadev.dogmatix.util.CloudBackupEngine
import com.cortinadev.dogmatix.util.CloudErrors
import com.cortinadev.dogmatix.util.CloudNotConfiguredException
import com.cortinadev.dogmatix.util.DavException
import com.cortinadev.dogmatix.util.DavProblem
import com.cortinadev.dogmatix.util.DavStore
import com.cortinadev.dogmatix.util.WebDavPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The WebDAV connection the cloud backup and device sync share: the connection test (find the
 * working address, prepare the folder, prove it can be written) and a ready [Session] for the
 * saved settings. Settings that passed a test are used directly; untested or changed ones are
 * tested first, so a background run never writes to a half-typed address. Nothing here logs the
 * user name or password.
 */
@Singleton
class CloudConnection @Inject constructor(
    private val settings: CloudSettings
) {
    /** A client for the saved login, the server address that answers and the app's folder there. */
    class Session(val store: DavStore, val serverUrl: String, val rootUrl: String)

    /**
     * Tests [config]: tries the addresses it may mean, creates the folder (with `backups` and
     * `sync`) when missing and writes a test file. When another address worked (Nextcloud's
     * `/remote.php/dav/files/<user>/`, a redirect) it is saved as the server address. The result
     * is recorded either way; throws what went wrong.
     */
    suspend fun test(config: CloudConfig? = null): CloudBackupEngine.Connection = withContext(Dispatchers.IO) {
        val tested = config ?: settings.config.first()
        if (!tested.isConfigured) throw CloudNotConfiguredException()
        val now = System.currentTimeMillis()
        try {
            val connection = CloudBackupEngine.connect(WebDavClient(tested.user, tested.password, tested.trustFingerprint), tested.server, tested.user, tested.folder)
            var saved = tested
            if (connection.serverUrl != WebDavPaths.normalizeServer(tested.server)) {
                settings.setServer(connection.serverUrl)
                saved = tested.copy(server = connection.serverUrl)
            }
            settings.recordTest(now, null, keyOf(saved))
            Log.i(TAG, "Connection test passed")
            connection
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            settings.recordTest(now, CloudErrors.encode(e), keyOf(tested))
            Log.w(TAG, "Connection test failed: ${problemOf(e)}")
            throw e
        }
    }

    /** A session for the saved settings; runs [test] first when they were not tested as they are now. */
    suspend fun open(): Session = withContext(Dispatchers.IO) {
        val config = settings.config.first()
        if (!config.isConfigured) throw CloudNotConfiguredException()
        val records = settings.records.first()
        val tested = records.lastTestAt > 0 && records.lastTestError.isEmpty() && records.lastTestKey == keyOf(config)
        if (tested) {
            val server = WebDavPaths.normalizeServer(config.server) ?: throw DavException(DavProblem.BAD_URL)
            val root = config.rootUrl ?: throw DavException(DavProblem.BAD_URL)
            Session(WebDavClient(config.user, config.password, config.trustFingerprint), server, root)
        } else {
            val connection = test(config)
            Session(WebDavClient(config.user, config.password, config.trustFingerprint), connection.serverUrl, connection.rootUrl)
        }
    }

    /**
     * A later call failed in a way that says the settings stopped working (password changed,
     * certificate replaced…): the test result is marked failed so the screen and the hub show it.
     */
    suspend fun noteFailure(error: Throwable) {
        if (!CloudMessages.isConnectionProblem(error)) return
        val config = settings.config.first()
        settings.recordTest(System.currentTimeMillis(), CloudErrors.encode(error), keyOf(config))
    }

    /**
     * Identifies the settings a test was made with, so a test of other settings is never taken
     * for this one. A truncated SHA-256: the password itself is never stored a second time.
     */
    fun keyOf(config: CloudConfig): String {
        val text = listOf(config.server.trim(), config.user.trim(), config.password, config.folder, config.trustFingerprint).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun problemOf(e: Throwable): String = (e as? DavException)?.problem?.name ?: e.javaClass.simpleName

    private companion object {
        const val TAG = "CloudConnection"
    }
}
