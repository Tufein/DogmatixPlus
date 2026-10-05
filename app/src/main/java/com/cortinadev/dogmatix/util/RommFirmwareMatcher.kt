package com.cortinadev.dogmatix.util

import com.google.gson.JsonElement
import java.net.URLEncoder

/** One firmware (BIOS) file RomM keeps for a platform (`GET /api/firmware`). Hashes are lower-case hex or null. */
data class RommFirmware(
    val id: Int,
    val fileName: String,
    val sizeBytes: Long,
    val md5: String?,
    val sha1: String?,
    val crc: String?,
    /** The platform it belongs to; null when the server did not say. */
    val platformId: Int?
)

/**
 * "Fetch missing BIOS from RomM" (5.0, BIOS tool): which of the BIOS files the check found
 * missing the RomM server has, and where each one goes. A file is matched by MD5 against the
 * known good dumps (on any platform: the same bytes are the same file), or — when the catalogue
 * has no checksum for it, or the server lists none — by name on a platform that belongs to the
 * same system. A download is only kept when it [verify]s.
 */
object RommFirmwareMatcher {

    /** A missing catalogue file. [path] is relative to the BIOS folder (`dc/dc_boot.bin`, `scph*.bin`). */
    data class Wanted(val system: String, val path: String, val md5: List<String>, val minSize: Long = 0, val required: Boolean = false) {
        val fileName: String get() = path.substringAfterLast('/')
        val folder: String get() = path.substringBeforeLast('/', "")
        val isPattern: Boolean get() = fileName.contains('*')
    }

    /** [firmware] fills [wanted]; it is written as [targetPath] (relative to the BIOS folder). */
    data class Match(val wanted: Wanted, val firmware: RommFirmware, val targetPath: String, val byHash: Boolean) {
        val targetFolder: String get() = targetPath.substringBeforeLast('/', "")
        val targetName: String get() = targetPath.substringAfterLast('/')
    }

    data class Result(val matches: List<Match>, val notOnServer: List<Wanted>)

    /** A RomM platform as the mapping needs it. */
    data class PlatformRef(val id: Int, val slug: String, val fsSlug: String, val name: String)

    enum class Verdict { OK, WRONG_DUMP, CORRUPT, TOO_SMALL, EMPTY }

    /** Reads a firmware list (bare array or `{items}`); entries without id or name, or missing on the server's disk, are skipped. */
    fun parseList(json: JsonElement?, platformId: Int? = null): List<RommFirmware> = with(RommJson) {
        items(json).mapNotNull { o ->
            if (o.bool("missing_from_fs") == true) return@mapNotNull null
            val id = o.int("id") ?: return@mapNotNull null
            val name = (o.text("file_name") ?: o.text("fs_name") ?: o.text("name"))?.substringAfterLast('/') ?: return@mapNotNull null
            RommFirmware(
                id = id,
                fileName = name,
                sizeBytes = (o.long("file_size_bytes") ?: o.long("fs_size_bytes") ?: o.long("size"))?.takeIf { it >= 0 } ?: 0L,
                md5 = hex(o.text("md5_hash") ?: o.text("md5"), 32),
                sha1 = hex(o.text("sha1_hash") ?: o.text("sha1"), 40),
                crc = hex(o.text("crc_hash") ?: o.text("crc"), 8),
                platformId = o.int("platform_id") ?: platformId
            )
        }
    }

    /** The files the BIOS check reported missing, one entry per catalogue file. */
    fun missing(results: List<BiosCatalog.SystemResult>): List<Wanted> =
        results.flatMap { r ->
            r.files.filter { it.state == BiosCatalog.State.MISSING }.map { f ->
                Wanted(r.system.name, f.file.path, f.file.md5.map { it.lowercase() }, f.file.minSize, f.file.required)
            }
        }

    /**
     * Which RomM platforms belong to each BIOS system: the platforms the user mapped consoles to
     * ([consoleMap], console id → platform id), plus any server platform whose slug or name
     * [BiosCatalog.systemsFor] recognises.
     */
    fun platformsBySystem(
        systems: Collection<BiosSystem>,
        consoleMap: Map<String, Int>,
        platforms: List<PlatformRef>
    ): Map<String, Set<Int>> {
        val names = systems.map { it.name }.toSet()
        val out = HashMap<String, MutableSet<Int>>()
        consoleMap.forEach { (console, platformId) ->
            BiosCatalog.systemsFor(listOf(console)).forEach { s -> if (s.name in names) out.getOrPut(s.name) { HashSet() } += platformId }
        }
        platforms.forEach { p ->
            val labels = listOf(p.slug, p.fsSlug, p.name).filter { it.isNotBlank() }
            BiosCatalog.systemsFor(labels).forEach { s -> if (s.name in names) out.getOrPut(s.name) { HashSet() } += p.id }
        }
        return out
    }

    /**
     * Pairs each wanted file with the server's best firmware for it. Per wanted file: a firmware
     * with one of its known MD5s (on one of the system's platforms first), else — for files
     * without a known checksum, or firmware the server lists without one — the same name (or the
     * pattern, at least [Wanted.minSize]) on one of the system's platforms. A firmware the server
     * hashed differently is another dump and never matched by name.
     */
    fun match(wanted: List<Wanted>, firmware: List<RommFirmware>, systemPlatforms: Map<String, Set<Int>>): Result {
        val matches = ArrayList<Match>()
        val notFound = ArrayList<Wanted>()
        val taken = HashSet<String>()
        for (w in wanted) {
            val platforms = systemPlatforms[w.system].orEmpty()
            fun onSystem(f: RommFirmware) = f.platformId != null && f.platformId in platforms
            val byHash = if (w.md5.isEmpty()) null else firmware
                .filter { it.md5 != null && it.md5 in w.md5 }
                .sortedWith(compareByDescending<RommFirmware> { onSystem(it) }.thenByDescending { it.fileName.equals(w.fileName, ignoreCase = true) }.thenBy { it.id })
                .firstOrNull()
            val byName = byHash ?: firmware
                .filter { onSystem(it) && nameFits(w, it) && (w.md5.isEmpty() || it.md5 == null) }
                .sortedWith(compareByDescending<RommFirmware> { it.md5 != null }.thenBy { it.fileName.lowercase() }.thenBy { it.id })
                .firstOrNull()
            val chosen = byHash ?: byName
            if (chosen == null) { notFound += w; continue }
            val target = targetPath(w, chosen)
            if (!taken.add(target.lowercase())) continue
            matches += Match(w, chosen, target, byHash != null)
        }
        return Result(matches, notFound)
    }

    /** Hash matches that only a listing of every platform found, for files [match] left over. */
    fun matchByHashOnly(wanted: List<Wanted>, firmware: List<RommFirmware>): Result =
        match(wanted.filter { it.md5.isNotEmpty() }, firmware, emptyMap()).let { r ->
            Result(r.matches.filter { it.byHash }, wanted.filter { w -> r.matches.none { it.wanted == w && it.byHash } })
        }

    /** Where a match is written: the catalogue's own path, or for a pattern the firmware's name in the pattern's folder. */
    fun targetPath(w: Wanted, f: RommFirmware): String =
        if (w.isPattern) listOf(w.folder, safeName(f.fileName)).filter { it.isNotEmpty() }.joinToString("/") else w.path

    /**
     * Whether downloaded bytes ([md5] of them, [size]) may be written: one of the known good dumps
     * when the catalogue lists any, else what the server's own hash or size promised.
     */
    fun verify(match: Match, md5: String, size: Long): Verdict {
        val actual = md5.trim().lowercase()
        return when {
            size <= 0L -> Verdict.EMPTY
            match.wanted.md5.isNotEmpty() -> if (actual in match.wanted.md5) Verdict.OK else Verdict.WRONG_DUMP
            size < match.wanted.minSize -> Verdict.TOO_SMALL
            match.firmware.md5 != null -> if (actual == match.firmware.md5) Verdict.OK else Verdict.CORRUPT
            match.firmware.sizeBytes > 0 -> if (size == match.firmware.sizeBytes) Verdict.OK else Verdict.CORRUPT
            else -> Verdict.OK
        }
    }

    /** The download route of a firmware file, below the server's base URL. */
    fun contentPath(f: RommFirmware): String =
        "/api/firmware/${f.id}/content/" + URLEncoder.encode(f.fileName, "UTF-8").replace("+", "%20")

    private fun nameFits(w: Wanted, f: RommFirmware): Boolean {
        if (!w.isPattern) return f.fileName.equals(w.fileName, ignoreCase = true)
        val regex = Regex(w.fileName.lowercase().split('*').joinToString(".*") { Regex.escape(it) })
        return regex.matches(f.fileName.lowercase()) && (f.sizeBytes == 0L || f.sizeBytes >= w.minSize)
    }

    /** A server file name made safe to write: no folders, no characters SAF providers refuse. */
    private fun safeName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("""[:*?"<>|\u0000-\u001f]"""), "_").trim().ifEmpty { "firmware.bin" }

    private fun hex(value: String?, length: Int): String? =
        value?.trim()?.lowercase()?.takeIf { it.length == length && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
}
