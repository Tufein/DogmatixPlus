package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Signs image requests to the RomM server (covers, screenshots, avatars) with the configured
 * token, the way the rest of the app talks to RomM. Other hosts never see the token.
 */
@Singleton
class RommImageAuth @Inject constructor(settingsRepository: SettingsRepository) : Interceptor {
    @Volatile private var base: String = ""
    @Volatile private var header: String = ""

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            combine(settingsRepository.rommUrl, settingsRepository.rommToken) { url, token -> url.trim().trimEnd('/') to token.trim() }
                .collect { (url, token) ->
                    base = url
                    header = if (url.isEmpty() || token.isEmpty()) "" else RommClient.authHeader(token)
                }
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val b = base
        val h = header
        val signed = if (h.isNotEmpty() && b.isNotEmpty() && request.header("Authorization") == null &&
            sameServer(b, request.url)
        ) request.newBuilder().header("Authorization", h).build() else request
        return chain.proceed(signed)
    }

    /** Same scheme, host and port as the configured address, and below its path (OkHttp has normalised the request's URL). */
    private fun sameServer(base: String, url: okhttp3.HttpUrl): Boolean {
        val configured = base.toHttpUrlOrNull() ?: return false
        if (configured.scheme != url.scheme || configured.host != url.host || configured.port != url.port) return false
        val prefix = configured.encodedPath.trimEnd('/')
        return prefix.isEmpty() || url.encodedPath == prefix || url.encodedPath.startsWith("$prefix/")
    }
}
