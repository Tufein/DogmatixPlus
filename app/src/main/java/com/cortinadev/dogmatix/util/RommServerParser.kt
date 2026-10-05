package com.cortinadev.dogmatix.util

import com.google.gson.JsonElement

/**
 * Reads what a RomM server says about itself (5.0 Cloud hub): `/api/heartbeat` (version),
 * `/api/users/me` (the account) and `/api/stats` (library counts). Every RomM release from 3.x
 * to 5.x is accepted; a field that is missing or has another shape simply stays null.
 */
object RommServerParser {

    data class User(val id: Int?, val username: String, val role: String, val avatarPath: String?)

    data class Stats(
        val platforms: Int? = null,
        val roms: Int? = null,
        val saves: Int? = null,
        val states: Int? = null,
        val screenshots: Int? = null,
        val totalBytes: Long? = null
    )

    /**
     * The server version from a heartbeat: `SYSTEM.VERSION` (RomM 3.5 and later), a top-level
     * `VERSION` (older), or a lower-case `version` / `system.version`. "development" builds are
     * returned as they are.
     */
    fun version(heartbeat: JsonElement?): String? {
        with(RommJson) {
            val o = obj(heartbeat) ?: return null
            val candidates = listOf(
                o.at("SYSTEM", "VERSION"), o.at("system", "version"), o.get("VERSION"), o.get("version"),
                o.at("SYSTEM", "version"), o.at("APP", "VERSION")
            )
            return candidates.firstNotNullOfOrNull { primitive(it)?.asString?.trim()?.removePrefix("v")?.takeIf { v -> v.isNotEmpty() } }
        }
    }

    fun user(json: JsonElement?): User? {
        with(RommJson) {
            val o = obj(json) ?: return null
            val name = o.text("username") ?: o.text("name") ?: o.text("email") ?: return null
            return User(
                id = o.int("id"),
                username = name,
                role = o.text("role")?.substringAfterLast('.')?.lowercase().orEmpty(),
                avatarPath = o.text("avatar_path") ?: o.text("avatar")
            )
        }
    }

    fun stats(json: JsonElement?): Stats {
        with(RommJson) {
            val o = obj(json) ?: return Stats()
            fun count(vararg names: String): Int? = names.firstNotNullOfOrNull { n -> o.int(n) ?: o.int(n.lowercase()) }?.takeIf { it >= 0 }
            return Stats(
                platforms = count("PLATFORMS"),
                roms = count("ROMS", "GAMES"),
                saves = count("SAVES"),
                states = count("STATES"),
                screenshots = count("SCREENSHOTS"),
                totalBytes = listOf("TOTAL_FILESIZE_BYTES", "TOTAL_FILESIZE", "FILESIZE", "total_filesize_bytes", "filesize")
                    .firstNotNullOfOrNull { o.long(it) }?.takeIf { it >= 0 }
            )
        }
    }

    /**
     * Where the server serves a user's avatar. RomM keeps uploaded avatars with the other user
     * assets (`/assets/romm/assets/<avatar_path>`).
     */
    fun avatarUrl(baseUrl: String, avatarPath: String?): String? =
        RommJson.serverUrl(baseUrl, avatarPath, defaultPrefix = "/assets/romm/assets/")

    /** `5.3.1`, `v4.0.0-beta.2`, `3.10` → numeric parts; null for "development" and the like. */
    fun versionParts(version: String?): List<Int>? {
        val core = version?.trim()?.removePrefix("v")?.removePrefix("V")?.substringBefore('-')?.substringBefore('+') ?: return null
        val parts = core.split('.').map { it.toIntOrNull() ?: return null }
        return parts.takeIf { it.isNotEmpty() }
    }

    /** True / false when [version] is (not) at least [major].[minor].[patch]; null when unknown. */
    fun atLeast(version: String?, major: Int, minor: Int = 0, patch: Int = 0): Boolean? {
        val parts = versionParts(version) ?: return null
        val have = listOf(parts.getOrElse(0) { 0 }, parts.getOrElse(1) { 0 }, parts.getOrElse(2) { 0 })
        val want = listOf(major, minor, patch)
        for (i in 0..2) if (have[i] != want[i]) return have[i] > want[i]
        return true
    }

    /** A short label for the hub: "5.3.1" stays, a development build reads "dev". */
    fun shortVersion(version: String?): String? = version?.let { v ->
        if (versionParts(v) != null) v.removePrefix("v") else if (v.contains("dev", ignoreCase = true)) "dev" else v.take(16)
    }
}
