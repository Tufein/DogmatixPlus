package com.cortinadev.dogmatix.util

/** Picking and checking the files of a GitHub release for the in-app update. */
object UpdateAssets {
    const val RELEASE_APK = "DogmatixPlus-release.apk"
    const val DEBUG_APK = "DogmatixPlus-debug.apk"
    const val CHECKSUMS = "SHA256SUMS.txt"

    /** The APK that matches the installed build (a debug build updates to the debug APK). */
    fun apkName(debugBuild: Boolean): String = if (debugBuild) DEBUG_APK else RELEASE_APK

    /** The SHA-256 a `sha256sum` style file gives for [fileName] (`<hex>  <name>` or `<hex> *<name>`), lower-case. */
    fun checksumFor(sums: String, fileName: String): String? =
        sums.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { line ->
            val parts = line.split(Regex("\\s+"), limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val name = parts[1].trim().removePrefix("*").substringAfterLast('/')
            if (name == fileName && parts[0].matches(Regex("[0-9a-fA-F]{64}"))) parts[0].lowercase() else null
        }.firstOrNull()
}
