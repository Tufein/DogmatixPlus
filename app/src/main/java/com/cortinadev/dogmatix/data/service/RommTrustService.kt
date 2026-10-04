package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/**
 * Keeps [TlsTrust] in step with Settings → RomM (server URL and the certificate fingerprint the
 * user confirmed), and reads a server's certificate for the "trust this server?" question.
 */
@Singleton
class RommTrustService @Inject constructor(private val settingsRepository: SettingsRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            combine(settingsRepository.rommUrl, settingsRepository.rommTrustFingerprint) { url, fp -> url to fp }
                .collect { (url, fp) -> TlsTrust.configure(url, fp) }
        }
    }

    /** The certificate [url] presents, or null when it is not an HTTPS server or cannot be reached. */
    suspend fun inspect(url: String): ServerCertificate? = withContext(Dispatchers.IO) { TlsTrust.probe(url) }

    suspend fun trust(fingerprint: String) { settingsRepository.setRommTrustFingerprint(fingerprint) }

    suspend fun forget() { settingsRepository.setRommTrustFingerprint("") }
}
