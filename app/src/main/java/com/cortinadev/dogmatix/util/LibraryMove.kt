package com.cortinadev.dogmatix.util

/**
 * Moving the library to another storage: copy each file, check the copy, and only then delete the
 * original. The decisions that protect the games are here so they can be tested. Pure JVM.
 */
object LibraryMove {
    /** A file of the library: [dirPath] is its folder below the library root ("" for the root, "GBA/hacks"). */
    data class Item(val dirPath: String, val name: String, val size: Long)

    enum class Outcome { COPIED, ALREADY_THERE, FAILED }

    /** Room to leave free on the target besides the games themselves. */
    const val RESERVE_BYTES = 100L * 1024 * 1024

    fun totalBytes(items: List<Item>): Long = items.sumOf { it.size.coerceAtLeast(0) }

    /** True/false when the free space is known, null when it is not (then nothing is refused). */
    fun fits(totalBytes: Long, freeBytes: Long?): Boolean? = freeBytes?.let { it - RESERVE_BYTES >= totalBytes }

    /** The original goes only when the copy is there and as big as the original. */
    fun mayDeleteOriginal(outcome: Outcome, originalSize: Long, copySize: Long): Boolean =
        outcome != Outcome.FAILED && originalSize == copySize

    /** A file already at the target with exactly the original's size counts as a copy from an earlier, interrupted run. */
    fun alreadyThere(existingSize: Long?, originalSize: Long): Boolean = existingSize != null && originalSize > 0 && existingSize == originalSize

    /** Two folders (canonical paths) that are the same or inside one another cannot be moved into each other. */
    fun overlaps(a: String, b: String): Boolean {
        val x = a.trimEnd('/').lowercase()
        val y = b.trimEnd('/').lowercase()
        return x == y || x.startsWith("$y/") || y.startsWith("$x/")
    }

    fun join(dirPath: String, name: String): String = if (dirPath.isEmpty()) name else "$dirPath/$name"
}
