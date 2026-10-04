package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.local.entity.WishlistEntity
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The wishlist as a small file that another device (or a later reinstall) can read: titles and
 * consoles only, so an imported wish starts as "wanted" again. Pure JVM for the tests.
 */
object WishlistShare {
    const val FORMAT = "dogmatix-wishlist"
    const val VERSION = 1

    fun export(rows: List<WishlistEntity>): String = JsonObject().apply {
        addProperty("format", FORMAT)
        addProperty("version", VERSION)
        add("items", BackupJson.wishlistToJson(rows.map { it.copy(notifiedAt = null) }))
    }.toString()

    /** The wishes in [text], or null when it is not a wishlist file. A damaged row is skipped. */
    fun parse(text: String): List<WishlistEntity>? = runCatching {
        val root = JsonParser.parseString(text.trimStart('\uFEFF')).asJsonObject
        if (root.get("format")?.asString != FORMAT) return null
        BackupJson.wishlistFromJson(root.get("items"))
    }.getOrNull()
}
