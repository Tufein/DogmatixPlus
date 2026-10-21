package com.tufein.valkyr.data.service

import com.tufein.valkyr.util.CoverSources
import com.tufein.valkyr.util.LibretroThumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds a game's box art on libretro-thumbnails (no API key needed) and resolves GitHub's
 * symlink stubs to the real image. Results, including misses, are kept for the app's run.
 */
@Singleton
class ThumbnailService @Inject constructor() {
    private val cache = ConcurrentHashMap<String, String>()

    /** A direct image URL for [fileName] on [consoleId], or null when libretro has none. */
    suspend fun boxart(consoleId: String, fileName: String): String? = withContext(Dispatchers.IO) {
        val system = LibretroThumbnails.systemFor(consoleId) ?: return@withContext null
        val key = "${system.repo}|$fileName"
        cache[key]?.let { return@withContext it.ifEmpty { null } }
        var found: String? = null
        for (name in LibretroThumbnails.candidates(fileName)) {
            found = resolve(system, name, hops = 2)
            if (found != null) break
        }
        cache[key] = found.orEmpty()
        found
    }

    /**
     * 4.3.0, the cover source chain: box art under every name of [LibretroThumbnails.plan] (the
     * user's [regions] first), then title screens and snaps when [nonBoxArt] allows them. A miss
     * is only a miss when every request was answered; a network error or a refusal (GitHub's rate
     * limit) makes the answer [CoverSources.Answer.Unavailable], which is not stored as a miss.
     */
    suspend fun find(consoleId: String, fileName: String, regions: List<String>, nonBoxArt: Boolean): CoverSources.Answer = withContext(Dispatchers.IO) {
        val system = LibretroThumbnails.systemFor(consoleId) ?: return@withContext CoverSources.Answer.Miss
        val key = "${system.repo}|$fileName|$nonBoxArt|${regions.take(3).joinToString(",")}"
        chainCache[key]?.let { return@withContext if (it.isEmpty()) CoverSources.Answer.Miss else CoverSources.Answer.Found(it) }
        var failed = false
        for ((art, name) in LibretroThumbnails.plan(fileName, regions, nonBoxArt)) {
            when (val probe = probe(system, art, name, hops = 2)) {
                is Probe.Found -> { chainCache[key] = probe.url; return@withContext CoverSources.Answer.Found(probe.url) }
                Probe.Absent -> Unit
                Probe.Failed -> failed = true
            }
        }
        if (failed) CoverSources.Answer.Unavailable else CoverSources.Answer.Miss.also { chainCache[key] = "" }
    }

    private val chainCache = ConcurrentHashMap<String, String>()

    private sealed interface Probe {
        data class Found(val url: String) : Probe
        data object Absent : Probe
        data object Failed : Probe
    }

    private fun probe(system: LibretroThumbnails.System, art: LibretroThumbnails.Art, name: String, hops: Int): Probe {
        val url = LibretroThumbnails.artUrl(system, art, name)
        val (code, head) = try { status(url) } catch (e: Exception) { return Probe.Failed }
        if (code == 404) return Probe.Absent
        if (code != 200 || head == null) return Probe.Failed
        LibretroThumbnails.symlinkTarget(head)?.let { target -> return if (hops > 0) probe(system, art, target, hops - 1) else Probe.Absent }
        return Probe.Found(url)
    }

    /** The status code and, for a 200, the body's first bytes. */
    private fun status(url: String): Pair<Int, ByteArray?> {
        val c = URL(url).openConnection() as HttpURLConnection
        TlsTrust.apply(c)
        return try {
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "Valkyr")
            val code = c.responseCode
            if (code != 200) code to null else code to c.inputStream.use { s ->
                val buf = ByteArray(300); var n = 0
                while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                buf.copyOf(n)
            }
        } finally { c.disconnect() }
    }

    private fun resolve(system: LibretroThumbnails.System, name: String, hops: Int): String? {
        val url = LibretroThumbnails.boxartUrl(system, name)
        val head = runCatching { firstBytes(url) }.getOrNull() ?: return null
        LibretroThumbnails.symlinkTarget(head)?.let { target -> return if (hops > 0) resolve(system, target, hops - 1) else null }
        return url
    }

    /** The status-200 body's first bytes (enough to tell a PNG from a symlink stub); null otherwise. */
    private fun firstBytes(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        TlsTrust.apply(c)
        return try {
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "Valkyr")
            if (c.responseCode != 200) null else c.inputStream.use { s ->
                val buf = ByteArray(300); var n = 0
                while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                buf.copyOf(n)
            }
        } finally { c.disconnect() }
    }
}
