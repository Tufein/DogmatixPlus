package com.cortinadev.dogmatix.data.service

import com.cortinadev.dogmatix.util.LibretroThumbnails
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
            c.setRequestProperty("User-Agent", "DogmatixPlus")
            if (c.responseCode != 200) null else c.inputStream.use { s ->
                val buf = ByteArray(300); var n = 0
                while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                buf.copyOf(n)
            }
        } finally { c.disconnect() }
    }
}
