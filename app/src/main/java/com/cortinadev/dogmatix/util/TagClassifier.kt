package com.cortinadev.dogmatix.util

/**
 * Which kind of label a file tag is, so the library rows can give each kind its own subtle colour
 * (5.0): regions, languages, revisions, release kinds (demo, beta, hack…), video standards, and
 * everything else. Unlike the filter's tag categories this also recognises revisions ("Rev 1",
 * "v1.1"), and it is cheap enough to run for every chip of every visible row (patterns compiled
 * once, no allocation besides the upper-cased copy). Pure JVM for the tests.
 */
object TagClassifier {

    enum class Kind { REGION, LANGUAGE, REVISION, RELEASE, VIDEO, OTHER }

    private val language = Regex("^[A-Z]{2,3}$")
    private val version = Regex("^V\\d+(\\.\\d+)*[A-Z]?$")
    private val revision = Regex("^REV(ISION)?\\s*[0-9A-Z.]+$")
    private val releaseWords = listOf("BETA", "PROTO", "DEMO", "SAMPLE", "HACK", "PIRATE", "KIOSK", "DEBUG")

    fun kindOf(tag: String): Kind {
        val u = tag.trim().uppercase()
        if (u.isEmpty()) return Kind.OTHER
        return when {
            u in Constants.Tags.VIDEO_STANDARDS -> Kind.VIDEO
            u in Constants.Tags.CONTENT_TYPES || releaseWords.any { u.startsWith(it) } -> Kind.RELEASE
            revision.matches(u) || version.matches(u) || u.startsWith("VERSION ") || u.startsWith("VER ") -> Kind.REVISION
            u in Constants.Tags.ALL_REGIONS -> Kind.REGION
            language.matches(u) -> Kind.LANGUAGE
            else -> Kind.OTHER
        }
    }
}
