package com.tufein.valkyr.util

import java.io.File
import java.io.IOException

/**
 * Retention of the safety copies the save sync keeps before it replaces a save (`files/save-backups`,
 * see [VerifiedSafetyCopies]): the newest [keep] copies of each save file are always kept; older ones are
 * only ever *moved* to a recovery folder ([SafetyCopyTrash]), after the user saw the list, and brought
 * back with one tap for [TRASH_KEEP_MS].
 *
 * "Per game" is per save file: every file or slot (`Game.srm`, `Game.state3`) has its own newest copies.
 */
object SaveBackups {
    const val DEFAULT_KEEP = 5
    const val MIN_KEEP = 1
    const val MAX_KEEP = 50

    /** The sync's own housekeeping moves copies older than this (and beyond the newest [keep]) to the recovery folder. */
    const val AUTO_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    /** Copies moved to the recovery folder wait this long before they are removed for good. */
    const val TRASH_KEEP_MS = 14L * 24 * 60 * 60 * 1000

    fun clampKeep(value: Int): Int = value.coerceIn(MIN_KEEP, MAX_KEEP)

    /** Steps of the "backups kept" setting: 1..10, then every 5 up to [MAX_KEEP]. */
    fun nextKeep(current: Int, delta: Int): Int {
        val steps = (MIN_KEEP..10).toList() + (15..MAX_KEEP step 5).toList()
        val at = steps.indexOfLast { it <= current }.coerceAtLeast(0)
        return steps[(at + delta).coerceIn(0, steps.lastIndex)]
    }

    /** One group per save file: the kind and the path, case-insensitively (the planner matches paths that way). */
    private fun groupOf(copy: SafetyCopy): String = copy.kind.name + "|" + copy.path.lowercase()

    /** Newest first; the stamp in the folder name breaks ties of copies taken in the same second. */
    private val newestFirst = compareByDescending<SafetyCopy> { it.takenAt }.thenByDescending { it.relative }

    /** The copies beyond the newest [keep] of their save file, newest of the surplus first. */
    fun excess(copies: List<SafetyCopy>, keep: Int): List<SafetyCopy> {
        val n = clampKeep(keep)
        return copies.groupBy(::groupOf).values.flatMap { group -> group.sortedWith(newestFirst).drop(n) }
            .sortedWith(newestFirst)
    }

    /** The surplus copies that are also older than [maxAgeMs]: what a sync moves on its own. */
    fun expired(copies: List<SafetyCopy>, keep: Int, now: Long, maxAgeMs: Long = AUTO_MAX_AGE_MS): List<SafetyCopy> =
        excess(copies, keep).filter { now - it.takenAt > maxAgeMs }

    /** Bytes of [copies]. */
    fun totalBytes(copies: List<SafetyCopy>): Long = copies.sumOf { it.size.coerceAtLeast(0L) }
}

/**
 * The recovery folder of the safety copies: [move] puts copies (with their checksum receipts and, when
 * nothing else of their batch stays, the owner note) below `<trash>/<stamp>/` keeping their relative
 * path; [undoLast] brings the latest move back without overwriting anything; [purge] removes moves older
 * than the retention for good. Plain files, so the tests run on the JVM.
 */
class SafetyCopyTrash(private val root: File, private val trash: File) {

    /** Moves [copies]; returns how many were moved. A copy that cannot be moved stays where it is. */
    fun move(copies: List<SafetyCopy>, now: Long = System.currentTimeMillis()): Int {
        if (copies.isEmpty()) return 0
        val stamp = File(trash, "%013d".format(now)).also { it.mkdirs() }
        var moved = 0
        val batches = linkedSetOf<String>()
        for (copy in copies) {
            val source = File(root, copy.relative)
            if (!inside(root, source) || !source.isFile) continue
            if (!transfer(source, File(stamp, copy.relative))) continue
            File(source.path + ".sha256").takeIf { it.isFile }?.let { transfer(it, File(stamp, copy.relative + ".sha256")) }
            batches += copy.relative.substringBefore('/')
            moved++
        }
        // The owner note belongs to the copy it describes: it follows when nothing else of the batch stays.
        for (batch in batches) {
            val dir = File(root, batch)
            val owner = File(dir, "owner.json")
            if (owner.isFile && dir.walkTopDown().none { it.isFile && it.name != "owner.json" }) {
                transfer(owner, File(stamp, "$batch/owner.json"))
            }
            removeEmptyDirs(dir)
        }
        if (moved == 0) stamp.deleteRecursively()
        return moved
    }

    /** How many copies the latest move holds (what [undoLast] would bring back). */
    fun lastCount(): Int = latest()?.let { count(it) } ?: 0

    /** Brings the latest move back; files whose place is taken are left in the recovery folder. Returns the copies restored. */
    fun undoLast(): Int {
        val stamp = latest() ?: return 0
        var restored = 0
        stamp.walkTopDown().filter { it.isFile }.toList().forEach { file ->
            val relative = file.relativeTo(stamp).invariantSeparatorsPath
            val target = File(root, relative)
            if (!inside(root, target) || target.exists()) return@forEach
            if (transfer(file, target) && !relative.endsWith(".sha256") && !relative.endsWith("owner.json")) restored++
        }
        removeEmptyDirs(stamp)
        return restored
    }

    /** Removes moves older than [TRASH_KEEP_MS]; returns how many moves (folders) went. */
    fun purge(now: Long = System.currentTimeMillis()): Int {
        var purged = 0
        trash.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val at = dir.name.toLongOrNull() ?: dir.lastModified()
            if (now - at > SaveBackups.TRASH_KEEP_MS && dir.deleteRecursively()) purged++
        }
        return purged
    }

    private fun latest(): File? = trash.listFiles()?.filter { it.isDirectory && count(it) > 0 }
        ?.maxByOrNull { it.name.toLongOrNull() ?: it.lastModified() }

    private fun count(stamp: File): Int = stamp.walkTopDown().count { it.isFile && !it.name.endsWith(".sha256") && it.name != "owner.json" }

    private fun inside(base: File, file: File): Boolean = file.canonicalPath.startsWith(base.canonicalPath + File.separator)

    /** A rename inside one volume; nothing is copied or overwritten. */
    private fun transfer(from: File, to: File): Boolean {
        if (to.exists()) return false
        to.parentFile?.let { if (!it.mkdirs() && !it.isDirectory) throw IOException("Could not create the recovery folder") }
        return from.renameTo(to)
    }

    private fun removeEmptyDirs(dir: File) {
        if (!dir.isDirectory) return
        dir.listFiles()?.filter { it.isDirectory }?.forEach(::removeEmptyDirs)
        if (dir.listFiles().isNullOrEmpty()) dir.delete()
    }
}
