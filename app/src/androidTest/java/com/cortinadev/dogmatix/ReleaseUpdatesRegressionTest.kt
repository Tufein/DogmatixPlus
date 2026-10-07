package com.cortinadev.dogmatix

import com.cortinadev.dogmatix.data.model.GitHubRelease
import com.cortinadev.dogmatix.util.ReleaseUpdates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android uses ICU for regex compilation; JVM tests alone cannot verify this parser. */
class ReleaseUpdatesRegressionTest {
    @Test fun metadataParserInitializesOnAndroidAndReadsCompactAndMultilineComments() {
        // This first access compiles the production regex on the Android runtime.
        assertEquals(34L, ReleaseUpdates.versionCode(marker(34)))
        assertEquals(34L, ReleaseUpdates.versionCode(
            "Notes\n<!-- dogmatix-release: {\n" +
                "  \"versionName\": \"2.2.0\",\n  \"versionCode\": 34\n} -->\nChecksums"
        ))
        assertEquals(34L, ReleaseUpdates.versionCode(
            "<!-- dogmatix-release: {\"versionName\":\"2.2.0}\",\"versionCode\":34} -->"
        ))
    }

    @Test fun absentMalformedOrInvalidMetadataIsIgnoredOnAndroid() {
        val bodies = listOf(
            null, "", "<!-- dogmatix-release: nope -->",
            "<!-- dogmatix-release: {invalid json} -->",
            "<!-- dogmatix-release: {\"versionCode\":34 -->",
            "<!-- dogmatix-release: {} -->",
            "<!-- dogmatix-release: {\"versionCode\":0} -->",
            "<!-- dogmatix-release: {\"versionCode\":-1} -->",
            "<!-- dogmatix-release: {\"versionCode\":34.5} -->",
            "<!-- dogmatix-release: {\"versionCode\":\"34\"} -->",
            "<!-- dogmatix-release: {\"versionCode\":true} -->",
            "<!-- dogmatix-release: {\"versionCode\":999999999999999999999} -->",
        )
        bodies.forEach { body -> assertNull(body, ReleaseUpdates.versionCode(body)) }
    }

    @Test fun AndroidBuildNumberOffersRenamedReleaseWithoutOfferingSameOrOlderApk() {
        val newer = release("v2.2.0", 34)
        assertTrue(ReleaseUpdates.isNewer(newer, "8.2.0-beta.2", 33))
        assertTrue(ReleaseUpdates.isNewer(newer, "8.2.0-beta.2-debug", 33))
        assertFalse(ReleaseUpdates.isNewer(release("v2.1.0", 33), "8.2.0-beta.2", 33))
        assertFalse(ReleaseUpdates.isNewer(release("v99.0.0", 32), "2.2.0", 34))
        assertTrue(ReleaseUpdates.isNewer(release("v2.3.0", null), "2.2.0-debug", 34))
        assertFalse(ReleaseUpdates.isNewer(release("v2.1.0", null), "2.2.0", 34))
    }

    @Test fun AndroidPublicationDateSelectionDoesNotDependOnGitHubListOrder() {
        val old = release("v1.0.0", 12).copy(publishedAt = "2026-10-03T01:17:59Z")
        val current = release("v2.2.0", 34)
        val draft = release("v2.3.0", 35).copy(draft = true, publishedAt = "2026-10-09T00:00:00Z")
        assertEquals(current, ReleaseUpdates.latest(listOf(draft, old, current), false))
    }

    private fun marker(code: Long) =
        "<!-- dogmatix-release: {\"versionCode\":$code,\"versionName\":\"2.2.0\"} -->"

    private fun release(tag: String, code: Long?) = GitHubRelease(
        tagName = tag, name = tag, publishedAt = "2026-10-08T00:00:00Z",
        prerelease = false, draft = false, body = code?.let(::marker),
    )
}
