package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.ContentType
import com.cortinadev.dogmatix.data.model.UrlEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListingCheckTest {
    private val entry = UrlEntry("https://example.org/gba/")
    private fun prev(
        ok: Boolean = true, fingerprint: String? = ListingCheck.fingerprint(entry), etag: String? = "\"abc\"",
        lastModified: String? = null, body: String? = "h1", servedBy: String? = null, rows: Int = 10, files: Int = 10
    ) = ListingCheck.Previous(ok, fingerprint, etag, lastModified, body, servedBy, rows, files)

    @Test fun `a successful previous scan with the same set-up and its rows can be reused`() {
        assertTrue(ListingCheck.canReuse(entry, prev(), force = false))
    }

    @Test fun `forced, failed, changed set-up, missing rows or no previous scan mean a full scan`() {
        assertFalse(ListingCheck.canReuse(entry, prev(), force = true))
        assertFalse(ListingCheck.canReuse(entry, prev(ok = false), force = false))
        assertFalse(ListingCheck.canReuse(entry.copy(contentType = ContentType.MISCELLANEOUS), prev(), force = false))
        assertFalse(ListingCheck.canReuse(entry, prev(rows = 0), force = false))
        assertFalse(ListingCheck.canReuse(entry, prev(rows = 7), force = false))
        assertFalse(ListingCheck.canReuse(entry, null, force = false))
    }

    @Test fun `an empty listing that stayed empty is reusable`() {
        assertTrue(ListingCheck.canReuse(entry, prev(rows = 0, files = 0), force = false))
    }

    @Test fun `conditional headers only go to the address that answered last time`() {
        assertEquals(mapOf("If-None-Match" to "\"abc\""), ListingCheck.conditionalHeaders(entry, prev(), false, entry.url))
        assertEquals(emptyMap<String, String>(), ListingCheck.conditionalHeaders(entry, prev(), false, "https://mirror.example/gba/"))
        val viaMirror = prev(servedBy = "https://mirror.example/gba/", etag = null, lastModified = "Tue, 01 Oct 2026 10:00:00 GMT")
        assertEquals(mapOf("If-Modified-Since" to "Tue, 01 Oct 2026 10:00:00 GMT"),
            ListingCheck.conditionalHeaders(entry, viaMirror, false, "https://mirror.example/gba/"))
    }

    @Test fun `same body is recognised by its hash`() {
        assertTrue(ListingCheck.sameBody(entry, prev(body = ListingCheck.hash("x".toByteArray())), false, ListingCheck.hash("x".toByteArray())))
        assertFalse(ListingCheck.sameBody(entry, prev(body = ListingCheck.hash("x".toByteArray())), false, ListingCheck.hash("y".toByteArray())))
    }

    @Test fun `only magnets with an info hash are skipped without fetching`() {
        val magnet = UrlEntry("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=x")
        val p = ListingCheck.Previous(true, ListingCheck.fingerprint(magnet), null, null, null, null, 5, 5)
        assertTrue(ListingCheck.canSkipMagnet(magnet, p, false))
        assertFalse(ListingCheck.canSkipMagnet(magnet, p, true))
        assertFalse(ListingCheck.canSkipMagnet(UrlEntry("https://x/y.torrent"), p, false))
        assertFalse(ListingCheck.canSkipMagnet(magnet.copy(folders = listOf("USA")), p, false))
    }
}
