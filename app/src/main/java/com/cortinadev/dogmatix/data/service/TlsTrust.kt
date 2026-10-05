package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.util.CertTrust
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** What a server presented when asked for its certificate, for the "trust this server?" question. */
data class ServerCertificate(
    val fingerprint: String,
    val subject: String,
    val issuer: String,
    val validUntil: Long,
    /** The system would trust it on its own (no need to ask). */
    val trustedBySystem: Boolean
)

/**
 * Trust-on-first-use for the RomM server. When the user confirmed a certificate fingerprint for
 * the configured host, HTTPS connections to *that host only* accept the certificate with exactly
 * that fingerprint even when the system does not trust it (self-signed, private CA) or when its
 * name does not match (an IP address). Every other host, and every other certificate, goes
 * through the normal system checks.
 */
object TlsTrust {
    @Volatile private var pinnedHost: String = ""
    @Volatile private var pinnedFingerprint: String = ""
    @Volatile private var pinnedFactory: SSLSocketFactory? = null

    fun configure(serverUrl: String, fingerprint: String) {
        val host = CertTrust.hostOf(serverUrl)
        val fp = CertTrust.normalize(fingerprint)
        if (host.isEmpty() || !CertTrust.isValid(fp) || !CertTrust.isHttps(serverUrl)) {
            pinnedHost = ""; pinnedFingerprint = ""; pinnedFactory = null
            return
        }
        pinnedFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(PinnedTrustManager(fp)), null) }.socketFactory
        pinnedFingerprint = fp
        pinnedHost = host
    }

    /** Lets [connection] accept the pinned certificate when it goes to the pinned host. */
    fun apply(connection: HttpURLConnection) {
        val https = connection as? HttpsURLConnection ?: return
        val factory = pinnedFactory ?: return
        if (!https.url.host.equals(pinnedHost, ignoreCase = true)) return
        https.sslSocketFactory = factory
        val fp = pinnedFingerprint
        val fallback = HttpsURLConnection.getDefaultHostnameVerifier()
        https.hostnameVerifier = HostnameVerifier { host, session ->
            val leaf = runCatching { session.peerCertificates.firstOrNull() }.getOrNull()
            (leaf != null && CertTrust.matches(fp, CertTrust.sha256Hex(leaf.encoded))) || fallback.verify(host, session)
        }
    }

    /**
     * For HTTP clients built once (Coil's OkHttp for cover images): a trust manager that accepts
     * the certificate pinned at the moment of the handshake and otherwise defers to the system.
     */
    val liveTrustManager: X509TrustManager by lazy {
        val system: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
            init(null as KeyStore?)
            trustManagers.filterIsInstance<X509TrustManager>().first()
        }
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) = system.checkClientTrusted(chain, authType)
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
                val fp = pinnedFingerprint
                val leaf = chain.firstOrNull()
                if (fp.isNotEmpty() && leaf != null && CertTrust.matches(fp, CertTrust.sha256Hex(leaf.encoded))) return
                system.checkServerTrusted(chain, authType)
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
        }
    }

    /** Socket factory for [liveTrustManager]. */
    val liveSocketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(liveTrustManager), null) }.socketFactory
    }

    /** Host check that also accepts the pinned certificate on the pinned host. */
    val liveHostnameVerifier: HostnameVerifier = HostnameVerifier { host, session ->
        val fp = pinnedFingerprint
        val leaf = runCatching { session.peerCertificates.firstOrNull() }.getOrNull()
        (fp.isNotEmpty() && host.equals(pinnedHost, ignoreCase = true) && leaf != null && CertTrust.matches(fp, CertTrust.sha256Hex(leaf.encoded))) ||
            HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
    }

    /**
     * Opens a TLS connection to [url] only to read the certificate: no HTTP request is sent, so no
     * credentials leave the device. Returns null when the server cannot be reached over HTTPS.
     */
    fun probe(url: String): ServerCertificate? = runCatching {
        val connection = URL(url.trim().trimEnd('/') + "/").openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            val systemTrusted = runCatching { connection.connect(); true }.getOrDefault(false)
            val certs = if (systemTrusted) connection.serverCertificates.toList() else readUntrusted(url)
            val leaf = certs.firstOrNull() as? X509Certificate ?: return null
            ServerCertificate(
                fingerprint = CertTrust.sha256Hex(leaf.encoded),
                subject = leaf.subjectX500Principal.name,
                issuer = leaf.issuerX500Principal.name,
                validUntil = leaf.notAfter.time,
                trustedBySystem = systemTrusted
            )
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** A handshake that accepts anything, used only to read the chain the server shows. */
    private fun readUntrusted(url: String): List<Certificate> {
        val captured = mutableListOf<Certificate>()
        val capture = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) { captured += chain }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(capture), null) }
        val connection = URL(url.trim().trimEnd('/') + "/").openConnection() as HttpsURLConnection
        try {
            connection.sslSocketFactory = context.socketFactory
            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.connect()
        } finally {
            connection.disconnect()
        }
        return captured
    }

    /** Accepts the pinned leaf certificate; anything else must satisfy the system trust store. */
    private class PinnedTrustManager(private val fingerprint: String) : X509TrustManager {
        private val system: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
            init(null as KeyStore?)
            trustManagers.filterIsInstance<X509TrustManager>().first()
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) = system.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("Empty certificate chain")
            if (CertTrust.matches(fingerprint, CertTrust.sha256Hex(leaf.encoded))) return
            system.checkServerTrusted(chain, authType)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }
}
