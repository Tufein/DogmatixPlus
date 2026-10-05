package com.cortinadev.dogmatix.util

import java.text.Normalizer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Names, listing and rotation of the encrypted backups in the cloud:
 * `<folder>/backups/dogmatix-<yyyyMMdd-HHmm>-<device>.dgxb`, the time in UTC (so the order is
 * the same whatever time zone each handheld is in) and the device as a short slug. Several
 * handhelds share one folder: each one only ever rotates (deletes) its own backups. Pure JVM for
 * the tests.
 */
object CloudBackupNames {

    const val PREFIX = "dogmatix-"
    const val EXTENSION = ".dgxb"
    const val DEFAULT_KEEP = 7
    /** The choices offered for "Backups to keep". */
    val KEEP_CHOICES = listOf(3, 5, 7, 10, 14, 30)
    /** Hours between automatic backups. */
    const val INTERVAL_HOURS = 24

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT)
    private val NAME = Regex("""^dogmatix-(\d{8}-\d{4})-([a-z0-9-]+)\.dgxb$""", RegexOption.IGNORE_CASE)

    /** A backup in the cloud, as the backup list shows it. */
    data class Listed(
        val name: String,
        /** Full URL of the file. */
        val url: String,
        /** When it was made (from the name, else the server's date); 0 when unknown. */
        val createdAt: Long,
        /** Device slug from the name; empty for a file named another way. */
        val device: String,
        val size: Long?,
        val isThisDevice: Boolean
    )

    /** "Retroid Pocket 5" → "retroid-pocket-5": lower-case ASCII letters, digits and dashes, at most 32. */
    fun deviceSlug(name: String): String {
        val ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val slug = ascii.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32).trim('-')
        return slug.ifEmpty { "device" }
    }

    fun fileName(at: Instant, device: String): String =
        PREFIX + STAMP.format(at.atOffset(ZoneOffset.UTC)) + "-" + deviceSlug(device) + EXTENSION

    /** (time in epoch ms, device slug) of a name made by [fileName]; null for any other name. */
    fun parse(name: String): Pair<Long, String>? {
        val m = NAME.matchEntire(name.trim()) ?: return null
        val time = runCatching { LocalDateTime.parse(m.groupValues[1], STAMP).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() ?: return null
        return time to m.groupValues[2].lowercase(Locale.ROOT)
    }

    /**
     * The `.dgxb` files among the members of the backups folder, newest first. [members] are
     * (name, size, lastModified) of the files there; sub-folders must already be left out.
     */
    fun listing(members: List<Triple<String, Long?, Long?>>, folderUrl: String, thisDevice: String): List<Listed> {
        val mine = deviceSlug(thisDevice)
        return members.filter { (name, _, _) -> name.endsWith(EXTENSION, ignoreCase = true) && !name.startsWith(".") }
            .map { (name, size, modified) ->
                val parsed = parse(name)
                Listed(
                    name = name,
                    url = WebDavPaths.child(folderUrl, name),
                    createdAt = parsed?.first ?: modified ?: 0L,
                    device = parsed?.second.orEmpty(),
                    size = size,
                    isThisDevice = parsed?.second == mine
                )
            }
            .sortedWith(compareByDescending<Listed> { it.createdAt }.thenByDescending { it.name })
    }

    /**
     * Names of [device]'s backups among [names] to delete so its newest [keep] stay. Backups of
     * other devices and files named another way are never touched.
     */
    fun toDelete(names: List<String>, device: String, keep: Int): List<String> {
        val mine = deviceSlug(device)
        return names.mapNotNull { name -> parse(name)?.takeIf { it.second == mine }?.let { name to it.first } }
            .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenByDescending { it.first })
            .drop(keep.coerceAtLeast(1))
            .map { it.first }
    }

    /** Due when there is none yet or the last one is [intervalHours] old (an hour of slack for the job's timing). */
    fun isDue(last: Long, now: Long, intervalHours: Int = INTERVAL_HOURS): Boolean =
        last <= 0 || now < last || now - last >= intervalHours * 3_600_000L - 3_600_000L

    /** The next value of "Backups to keep" after a ◀ ▶ press. */
    fun shiftKeep(current: Int, delta: Int): Int {
        val i = KEEP_CHOICES.indexOf(current).takeIf { it >= 0 } ?: KEEP_CHOICES.indexOf(DEFAULT_KEEP)
        return KEEP_CHOICES[(i + delta).coerceIn(0, KEEP_CHOICES.lastIndex)]
    }
}
