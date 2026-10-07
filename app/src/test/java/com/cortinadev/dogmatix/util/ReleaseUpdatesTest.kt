package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.GitHubRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseUpdatesTest {
    @Test
    fun `Android build number offers renumbered release despite lower label`() {
        val release = release("v2.2.0", code = 34)
        assertTrue(ReleaseUpdates.isNewer(release, "8.2.0-beta.2", 33))
        assertTrue(ReleaseUpdates.isNewer(release, "8.2.0-beta.2-debug", 33))
    }

    @Test
    fun `same APK with renamed label is not an update`() {
        val renamed = release("v2.1.0", code = 33)
        assertFalse(ReleaseUpdates.isNewer(renamed, "8.2.0-beta.2", 33))
        assertFalse(ReleaseUpdates.isNewer(renamed, "2.2.0", 34))
        assertFalse(ReleaseUpdates.isNewer(release("v99.0.0", code = 32), "2.2.0", 34))
    }

    @Test
    fun `older release without metadata keeps numeric fallback`() {
        assertTrue(ReleaseUpdates.isNewer(release("v2.3.0"), "2.2.0-debug", 34))
        assertFalse(ReleaseUpdates.isNewer(release("v2.1.0"), "2.2.0", 34))
        assertTrue(ReleaseUpdates.isNewer(release("v1.2.0"), "1.2.0-beta.1", 14))
    }

    @Test
    fun `metadata tolerates formatting and ignores invalid build numbers`() {
        assertEquals(34L, ReleaseUpdates.versionCode("Notes\n<!-- dogmatix-release: {\n\"versionName\":\"2.2.0\", \"versionCode\":34\n} -->\nChecksums"))
        listOf(null, "", "versionCode=34", "<!-- dogmatix-release: nope -->",
            "<!-- dogmatix-release: {\"versionCode\":0} -->",
            "<!-- dogmatix-release: {\"versionCode\":-1} -->",
            "<!-- dogmatix-release: {\"versionCode\":34.5} -->",
            "<!-- dogmatix-release: {\"versionCode\":\"34\"} -->",
            "<!-- dogmatix-release: {\"versionCode\":true} -->",
            "<!-- dogmatix-release: {\"versionCode\":999999999999999999999} -->",
            "<!-- dogmatix-release: {} -->").forEach { assertNull(ReleaseUpdates.versionCode(it)) }
    }

    @Test
    fun `published date decides latest regardless of GitHub list order`() {
        val old = release("v1.0.0", date = "2026-10-03T01:17:59Z", code = 12)
        val newer = release("v2.1.0", date = "2026-10-07T13:56:14Z", code = 33)
        val newest = release("v2.2.0", date = "2026-10-08T00:00:00Z", code = 34)
        assertEquals(newest, ReleaseUpdates.latest(listOf(old, newest, newer), false))
    }

    @Test
    fun `drafts and unselected prereleases are excluded`() {
        val stable = release("v2.2.0", code = 34)
        val preview = release("v2.3.0-beta.1", date = "2026-10-09T00:00:00Z", code = 35).copy(prerelease = true)
        val draft = release("v2.4.0", date = "2026-10-10T00:00:00Z", code = 36).copy(draft = true)
        assertEquals(stable, ReleaseUpdates.latest(listOf(draft, preview, stable), false))
        assertEquals(preview, ReleaseUpdates.latest(listOf(draft, preview, stable), true))
        assertNull(ReleaseUpdates.latest(listOf(draft), true))
    }

    private fun release(tag: String, date: String = "2026-10-08T00:00:00Z", code: Long? = null) =
        GitHubRelease(tag, tag, date, prerelease = false, draft = false,
            body = code?.let { "<!-- dogmatix-release: {\"versionCode\":$it,\"versionName\":\"${tag.removePrefix("v")}\"} -->" })
}
