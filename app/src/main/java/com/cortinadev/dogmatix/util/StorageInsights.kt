package com.cortinadev.dogmatix.util

/** Where the disk space of the collection goes, and whether what is queued still fits. */
object StorageInsights {

    data class ConsoleUsage(val consoleId: String?, val scope: String, val games: Int, val bytes: Long)

    /** Space per console (or per unknown folder), biggest first. */
    fun usageByConsole(entries: List<GameEntry>): List<ConsoleUsage> =
        entries.groupBy { it.consoleId ?: it.scope }.map { (_, group) ->
            ConsoleUsage(group.first().consoleId, group.first().scope, group.size, group.sumOf { it.size })
        }.sortedByDescending { it.bytes }

    /** The [count] biggest games on disk. */
    fun biggest(entries: List<GameEntry>, count: Int): List<GameEntry> =
        entries.sortedByDescending { it.size }.take(count)

    data class QueueItem(val remainingBytes: Long, val extractable: Boolean)

    /** What the downloads still in the queue need: bytes left, plus room to unpack archives. */
    data class QueueNeed(val downloadBytes: Long, val unpackBytes: Long) {
        val total: Long get() = downloadBytes + unpackBytes
    }

    fun queueNeed(items: List<QueueItem>): QueueNeed =
        QueueNeed(items.sumOf { it.remainingBytes.coerceAtLeast(0) }, items.filter { it.extractable }.sumOf { it.remainingBytes.coerceAtLeast(0) })

    /** Bytes short of what the queue needs (0 = it fits); null when the free space is unknown. */
    fun shortfall(need: QueueNeed, freeBytes: Long?, margin: Long = 100L * 1024 * 1024): Long? =
        freeBytes?.let { (need.total + margin - it).coerceAtLeast(0) }

    fun isExtractable(extension: String): Boolean = extension.trim('.').lowercase() in setOf("zip", "7z", "rar")
}
