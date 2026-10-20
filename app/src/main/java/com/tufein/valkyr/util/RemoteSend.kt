package com.tufein.valkyr.util

import java.util.Locale

/**
 * Sending whole game packages (every file, folders kept) to the RomM server or to the NAS
 * (the WebDAV connection of Settings → Cloud). Pure JVM for the tests: the plan for RomM, the
 * paths on the NAS, and how each sent file is judged afterwards.
 *
 * RomM's upload takes one file per game, without folders. A game of several files, or of files
 * in folders, is therefore packed into one ZIP that keeps the relative paths ([StoredZip]); RomM
 * treats a ZIP as one game. A single loose file goes as it is.
 *
 * On the NAS the files keep their folders below `<app folder>/games/`, exactly as on the device;
 * the plan (what is there, clashes, saves left out) is [PackageCopyPlan]'s.
 */
object RemoteSend {
    /** The folder below the app's NAS folder that receives the games. */
    const val GAMES_FOLDER = "games"
    private const val MAX_RENAME = 99

    // ---- NAS -------------------------------------------------------------------------------

    /** The path segments of a destination path below the app's NAS folder. */
    fun davSegments(target: String): List<String> = listOf(GAMES_FOLDER) + target.split('/').filter { it.isNotEmpty() }

    /** The hidden name a file is written under first; it gets its real name once complete. */
    fun tempName(name: String): String = ".$name.dogmatix-part"

    // ---- RomM ------------------------------------------------------------------------------

    /** Whether a game must be packed: several files, files in folders, or names only case tells apart. */
    fun needsZip(paths: List<String>): Boolean = paths.size > 1 || RommUploadPlan.requiresArchive(paths)

    /** The name a game gets on RomM: the single file's own name, or the game's name with `.zip`. */
    fun rommName(title: String, paths: List<String>, n: Int = 1): String {
        val name = if (needsZip(paths)) PackageCopyPlan.folderName(title) + ".zip" else paths.single().substringAfterLast('/')
        if (n <= 1) return name
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
    }

    enum class RommDecision {
        /** Sent under [RommGame.name]. */
        SEND,
        /** RomM already has a file of this name and size; it is checked, not sent again. */
        PRESENT,
        /** Its name was taken by another file; it goes under a new name. */
        RENAMED,
        /** Its name was taken by another file; it is left out. */
        SKIPPED,
        /** Its console has no RomM platform in Settings → RomM. */
        NO_PLATFORM,
        /** Too large to pack without ZIP64 (4 GB or more). */
        TOO_LARGE,
        /** Nothing but save files. */
        ONLY_SAVES
    }

    data class RommGame(
        val key: String,
        val title: String,
        val consoleId: String,
        val platformId: Int?,
        val decision: RommDecision,
        /** The file name on RomM. */
        val name: String,
        val zipped: Boolean,
        /** The files that go (saves left out), in the order they are packed. */
        val parts: List<PackageCopyPlan.Part>,
        val saves: List<String>,
        /** Bytes of the file RomM receives (the ZIP when packed). */
        val bytes: Long
    ) {
        val sends: Boolean get() = decision == RommDecision.SEND || decision == RommDecision.RENAMED
    }

    /**
     * The plan for [sources]. [platformOf] maps a console to its RomM platform; [serverFiles]
     * lists the files RomM has on a platform (lower-case name → size), null when unknown.
     */
    fun rommPlan(
        sources: List<PackageCopyPlan.Source>,
        platformOf: (String) -> Int?,
        serverFiles: (Int) -> Map<String, Long>?,
        choice: CopyConflictChoice = CopyConflictChoice.SKIP
    ): List<RommGame> {
        val taken = HashMap<Int, MutableSet<String>>()
        return sources.map { source ->
            val saves = source.parts.filter { PackageCopyPlan.isSaveOrState(it.path) }.map { it.path }
            val parts = source.parts.filterNot { PackageCopyPlan.isSaveOrState(it.path) }
            val platform = platformOf(source.consoleId)
            fun game(decision: RommDecision, name: String = "", bytes: Long = 0) = RommGame(source.key, source.title, source.consoleId,
                platform, decision, name, parts.isNotEmpty() && needsZip(parts.map { it.path }), parts, saves, bytes)
            if (parts.isEmpty()) return@map game(RommDecision.ONLY_SAVES)
            val paths = parts.map { it.path }
            val zipped = needsZip(paths)
            val bytes = if (zipped) {
                val entries = parts.map { StoredZip.Entry(it.path, it.bytes, 0) }
                if (!StoredZip.fits(entries)) return@map game(RommDecision.TOO_LARGE, rommName(source.title, paths))
                StoredZip.size(entries)
            } else parts.single().bytes
            if (platform == null) return@map game(RommDecision.NO_PLATFORM, rommName(source.title, paths), bytes)
            val server = serverFiles(platform).orEmpty()
            val used = taken.getOrPut(platform) { HashSet() }
            fun key(name: String) = name.lowercase(Locale.ROOT)
            val name = rommName(source.title, paths)
            val onServer = server[key(name)]
            when {
                key(name) !in used && onServer == null -> { used += key(name); game(RommDecision.SEND, name, bytes) }
                key(name) !in used && onServer == bytes -> { used += key(name); game(RommDecision.PRESENT, name, bytes) }
                choice == CopyConflictChoice.SKIP -> game(RommDecision.SKIPPED, name, bytes)
                else -> {
                    val free = (2..MAX_RENAME).map { rommName(source.title, paths, it) }
                        .firstOrNull { key(it) !in used && key(it) !in server }
                    if (free == null) game(RommDecision.SKIPPED, name, bytes)
                    else { used += key(free); game(RommDecision.RENAMED, free, bytes) }
                }
            }
        }
    }

    // ---- Checking what arrived ---------------------------------------------------------------

    enum class Check {
        /** Same size and the same checksum. */
        HASH_OK,
        /** Same size; the other side gives no checksum to compare. */
        SIZE_OK,
        MISSING,
        SIZE_DIFFERS,
        HASH_DIFFERS
    }

    /** How a file on the other side compares with what was sent; [remoteSha256] null = not read back. */
    fun check(expectedBytes: Long, expectedSha256: String, remoteBytes: Long?, remoteSha256: String?): Check = when {
        remoteBytes == null -> Check.MISSING
        remoteBytes != expectedBytes -> Check.SIZE_DIFFERS
        remoteSha256 == null -> Check.SIZE_OK
        remoteSha256.equals(expectedSha256, ignoreCase = true) -> Check.HASH_OK
        else -> Check.HASH_DIFFERS
    }

    /**
     * Whether RomM's checksum of a file ([serverHash], `algorithm:hex` as [Checksums.best] gives
     * it) is the one of what was sent. Null when RomM lists none, or one of another kind. RomM
     * may checksum the files inside a ZIP rather than the ZIP itself, so a difference is not
     * treated as damage; only a match counts as a confirmed checksum.
     */
    fun rommHashMatches(serverHash: String?, sha1: String?, md5: String?, crc32: String?): Boolean? {
        val expected = Checksums.parse(serverHash) ?: return null
        val actual = when (expected.algo) {
            HashAlgo.SHA1 -> sha1
            HashAlgo.MD5 -> md5
            HashAlgo.CRC32 -> crc32
            HashAlgo.SHA256 -> null
        } ?: return null
        return Checksums.matches(expected, actual)
    }

    // ---- Results -----------------------------------------------------------------------------

    enum class Outcome {
        /** Sent, and the copy has the same size and checksum. */
        SENT_HASH,
        /** Sent, and the copy has the same size (no checksum to compare). */
        SENT_SIZE,
        /** Was already there, with the same checksum. */
        PRESENT_HASH,
        /** Was already there, with the same size. */
        PRESENT_SIZE,
        /** Sent; RomM does not list it yet (it shows up after its next scan). */
        WAITING,
        /** Another file of that name is in the way; nothing was replaced. */
        CONFLICT,
        /** The file on the device is no longer the one of the package (changed or missing). */
        CHANGED,
        /** Sending or checking failed. */
        FAILED,
        /** Not sent (stopped, or an earlier file of the game failed). */
        NOT_SENT
    }

    fun isDone(outcome: Outcome): Boolean = outcome == Outcome.SENT_HASH || outcome == Outcome.SENT_SIZE ||
        outcome == Outcome.PRESENT_HASH || outcome == Outcome.PRESENT_SIZE

    /** Whether trying again can help (a clash or a changed device file needs the user first). */
    fun canRetry(outcome: Outcome): Boolean = outcome == Outcome.FAILED || outcome == Outcome.NOT_SENT || outcome == Outcome.WAITING

    /** The outcome of a whole game from its files: the worst one wins. */
    fun gameOutcome(parts: List<Outcome>): Outcome = parts.maxByOrNull { severity(it) } ?: Outcome.NOT_SENT

    private fun severity(o: Outcome): Int = when (o) {
        Outcome.SENT_HASH, Outcome.PRESENT_HASH -> 0
        Outcome.SENT_SIZE, Outcome.PRESENT_SIZE -> 1
        Outcome.WAITING -> 2
        Outcome.NOT_SENT -> 3
        Outcome.CONFLICT -> 4
        Outcome.CHANGED -> 5
        Outcome.FAILED -> 6
    }
}
