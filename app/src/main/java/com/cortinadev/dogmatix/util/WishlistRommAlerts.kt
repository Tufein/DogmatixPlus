package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.local.entity.WishlistEntity

/**
 * Decides which wishes to announce as "Now on your RomM server" (6.0). A wish is announced once:
 * when the RomM game list holds it, the device does not already have it and its key is not in the
 * announced set. The source-based announcement (the wish's own `notifiedAt`) is not touched.
 * Pure JVM for the tests.
 */
object WishlistRommAlerts {

    /** Stable key of a wish for the announced set: normalised title plus console ('' = any). */
    fun announceKey(item: WishlistEntity): String = item.key + "|" + item.consoleId.orEmpty()

    /** Wishes that are on the server ([rommKeys]: `consoleId|name`) and were not announced yet. */
    fun pending(
        wishes: List<WishlistEntity>,
        rommKeys: Set<String>,
        announced: Set<String>,
        onDevice: (WishlistEntity) -> Boolean
    ): List<WishlistEntity> {
        if (rommKeys.isEmpty()) return emptyList()
        return wishes.filter { it.key.isNotEmpty() }
            .filter { announceKey(it) !in announced }
            .filter { WishlistMatch.inRomm(it.title, it.consoleId, rommKeys) }
            .filterNot(onDevice)
            .distinctBy { announceKey(it) }
    }
}
