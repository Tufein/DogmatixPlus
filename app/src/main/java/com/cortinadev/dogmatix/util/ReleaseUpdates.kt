package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.GitHubRelease
import com.google.gson.JsonParser
import java.time.Instant

/** Release labels can be renumbered; Android's build number still increases between APKs. */
object ReleaseUpdates {
    private val metadata = Regex(
        """<!--\s*dogmatix-release:\s*(\{.*?\})\s*-->""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /**
     * The release workflow writes:
     * `<!-- dogmatix-release: {"versionCode":34,"versionName":"2.2.0"} -->`.
     * Older releases without valid metadata keep their version-tag comparison.
     */
    fun versionCode(body: String?): Long? = try {
        val json = metadata.find(body.orEmpty())?.groupValues?.get(1)
        val value = json?.let { JsonParser.parseString(it).asJsonObject.get("versionCode") }
        value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asString?.toLongOrNull()?.takeIf { it > 0 }
    } catch (_: Exception) {
        null
    }

    fun isNewer(release: GitHubRelease, installedVersion: String, installedVersionCode: Long): Boolean =
        versionCode(release.body)?.let { it > installedVersionCode }
            ?: (VersionUtils.compareVersions(release.tagName, installedVersion) > 0)

    /** GitHub's list order follows tag/commit creation; publication order survives tag renaming. */
    fun latest(releases: List<GitHubRelease>, includePreReleases: Boolean): GitHubRelease? =
        releases.asSequence()
            .filter { !it.draft && (includePreReleases || !it.prerelease) }
            .maxWithOrNull(compareBy<GitHubRelease> { publishedAt(it) }
                .thenBy { versionCode(it.body) ?: 0 })

    private fun publishedAt(release: GitHubRelease): Long =
        runCatching { Instant.parse(release.publishedAt).toEpochMilli() }.getOrDefault(0)
}
