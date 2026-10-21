package com.tufein.valkyr.data.service

import com.tufein.valkyr.data.local.CoverSourceSettings
import com.tufein.valkyr.util.ScreenScraper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adds the ScreenScraper credentials to ScreenScraper image requests (4.3.0). Covers are stored
 * and cached without them (see [ScreenScraper.stripCredentials]); only the request that leaves the
 * device carries them, and only to screenscraper.fr. Same idea as [RommImageAuth].
 */
@Singleton
class ScreenScraperImageAuth @Inject constructor(private val settings: CoverSourceSettings) : Interceptor {
    @Volatile private var credentials = ScreenScraper.Credentials()
    @Volatile private var loaded = false

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            settings.credentials.collect { credentials = it; loaded = true }
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        if (!ScreenScraper.isApiUrl(url)) return chain.proceed(request)
        // A cover requested right after start, before the collector ran: read the settings once
        // (OkHttp's own thread, never the main thread).
        val c = if (loaded) credentials else runCatching { runBlocking { settings.credentials.first() } }.getOrDefault(credentials)
        if (!c.ready) return chain.proceed(request)
        val signed = ScreenScraper.withCredentials(url, c).toHttpUrlOrNull() ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().url(signed).build())
    }
}
