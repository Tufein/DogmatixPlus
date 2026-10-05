package com.cortinadev.dogmatix.util

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * The files of device sync, written and read by hand with fixed field names (like [BackupJson],
 * so R8 renaming classes can never break the format):
 *
 * `<folder>/sync/library.json` on the server, shared by all devices:
 * ```
 * { "format": "dogmatix-sync", "version": 1, "updatedAt": 1759400000000, "updatedBy": "Retroid Pocket 5",
 *   "devices": { "<device id>": { "name": "Retroid Pocket 5", "lastWrite": 1759400000000 } },
 *   "favourites": [ { "consoleId": "snes", "fileName": "Chrono Trigger (USA).sfc", "addedAt": 1759… } ],
 *   "wishlist": [ { "title": "Mother 3", "consoleId": "gba", "addedAt": 1759… } ],
 *   "collections": [ { "name": "Couch co-op", "createdAt": 1759…, "items": [ { "consoleId": …, "fileName": …, "addedAt": … } ] } ] }
 * ```
 * and the base of the three-way merge on this device (`files/device_sync_base.json`): the same
 * library plus the address of the server file it belongs to.
 *
 * Reading skips malformed rows instead of failing, and reports a file of a newer format so it is
 * never overwritten by an older app. Pure JVM for the tests.
 */
object DeviceSyncJson {

    const val FORMAT = "dogmatix-sync"
    const val BASE_FORMAT = "dogmatix-sync-base"
    const val VERSION = 1

    data class Device(val name: String, val lastWrite: Long)

    data class Document(
        val library: SyncLibrary,
        val updatedAt: Long = 0L,
        val updatedBy: String = "",
        val devices: Map<String, Device> = emptyMap(),
        /** Random mark of the write that produced this document; lets a writer check its write survived (see [DeviceSyncEngine]). */
        val rev: String = ""
    )

    /**
     * The state of this device's last sync. [account] identifies the login it was made with (see
     * [DeviceSyncEngine.accountKey]); empty for a base written before 6.0, which is trusted as it was.
     */
    data class Base(val remoteUrl: String, val library: SyncLibrary, val savedAt: Long = 0L, val account: String = "")

    /** Outcome of reading the server's file. */
    sealed class Parsed {
        data class Ok(val document: Document) : Parsed()
        /** Written by a newer Dogmatix: leave it alone. */
        data class Newer(val version: Int) : Parsed()
        /** Not a sync file at all (or damaged beyond reading). */
        object Invalid : Parsed()
    }

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    fun write(document: Document): String = gson.toJson(JsonObject().apply {
        addProperty("format", FORMAT)
        addProperty("version", VERSION)
        addProperty("updatedAt", document.updatedAt)
        addProperty("updatedBy", document.updatedBy)
        if (document.rev.isNotEmpty()) addProperty("rev", document.rev)
        add("devices", JsonObject().apply {
            document.devices.forEach { (id, d) ->
                add(id, JsonObject().apply { addProperty("name", d.name); addProperty("lastWrite", d.lastWrite) })
            }
        })
        writeLibrary(this, document.library)
    })

    fun read(text: String): Parsed {
        val root = runCatching { JsonParser.parseString(text) }.getOrNull() as? JsonObject ?: return Parsed.Invalid
        if (root.string("format") != FORMAT) return Parsed.Invalid
        val version = root.long("version")?.toInt() ?: return Parsed.Invalid
        if (version > VERSION) return Parsed.Newer(version)
        val devices = (root.get("devices") as? JsonObject)?.entrySet()?.mapNotNull { (id, el) ->
            val o = el as? JsonObject ?: return@mapNotNull null
            id to Device(o.string("name").orEmpty(), o.long("lastWrite") ?: 0L)
        }?.toMap().orEmpty()
        return Parsed.Ok(Document(readLibrary(root), root.long("updatedAt") ?: 0L, root.string("updatedBy").orEmpty(), devices, root.string("rev").orEmpty()))
    }

    fun writeBase(base: Base): String = gson.toJson(JsonObject().apply {
        addProperty("format", BASE_FORMAT)
        addProperty("version", VERSION)
        addProperty("remote", base.remoteUrl)
        addProperty("savedAt", base.savedAt)
        if (base.account.isNotEmpty()) addProperty("account", base.account)
        writeLibrary(this, base.library)
    })

    /** The stored base; null when missing, damaged or of another format (then the next sync is a union). */
    fun readBase(text: String): Base? {
        val root = runCatching { JsonParser.parseString(text) }.getOrNull() as? JsonObject ?: return null
        if (root.string("format") != BASE_FORMAT || (root.long("version") ?: 0L) != VERSION.toLong()) return null
        val remote = root.string("remote")?.takeIf { it.isNotBlank() } ?: return null
        return Base(remote, readLibrary(root), root.long("savedAt") ?: 0L, root.string("account").orEmpty())
    }

    private fun writeLibrary(target: JsonObject, library: SyncLibrary) {
        target.add("favourites", JsonArray().apply {
            library.favourites.forEach { (key, at) -> itemJson(key, at)?.let(::add) }
        })
        target.add("wishlist", JsonArray().apply {
            library.wishlist.values.forEach { w ->
                add(JsonObject().apply {
                    addProperty("title", w.title)
                    w.consoleId?.let { addProperty("consoleId", it) }
                    addProperty("addedAt", w.addedAt)
                })
            }
        })
        target.add("collections", JsonArray().apply {
            library.collections.values.forEach { c ->
                add(JsonObject().apply {
                    addProperty("name", c.name)
                    addProperty("createdAt", c.createdAt)
                    add("items", JsonArray().apply { c.items.forEach { (key, at) -> itemJson(key, at)?.let(::add) } })
                })
            }
        })
    }

    private fun readLibrary(root: JsonObject): SyncLibrary {
        val favourites = LinkedHashMap<String, Long>()
        root.array("favourites").forEach { el -> readItem(el)?.let { (k, t) -> favourites[k] = maxOf(t, favourites[k] ?: t) } }
        val wishlist = LinkedHashMap<String, SyncWish>()
        root.array("wishlist").forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val title = o.string("title")?.trim()?.takeIf { it.length >= 2 } ?: return@forEach
            val wish = SyncWish(title, o.string("consoleId")?.trim()?.takeIf { it.isNotEmpty() }, o.long("addedAt") ?: 0L)
            wishlist[DeviceSyncMerge.wishKey(wish.title, wish.consoleId)] = wish
        }
        val collections = LinkedHashMap<String, SyncCollection>()
        root.array("collections").forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            // Same limit as CollectionsRepository.create, so the key stays the same on every device.
            val name = o.string("name")?.trim()?.take(60)?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            val items = LinkedHashMap<String, Long>()
            o.array("items").forEach { item -> readItem(item)?.let { (k, t) -> items[k] = t } }
            val key = DeviceSyncMerge.collectionKey(name)
            val existing = collections[key]
            collections[key] = if (existing == null) SyncCollection(name, o.long("createdAt") ?: 0L, items)
            else existing.copy(items = existing.items + items)
        }
        return SyncLibrary(favourites, wishlist, collections)
    }

    private fun itemJson(key: String, at: Long): JsonObject? {
        val (console, file) = DeviceSyncMerge.splitItemKey(key) ?: return null
        return JsonObject().apply {
            addProperty("consoleId", console)
            addProperty("fileName", file)
            addProperty("addedAt", at)
        }
    }

    private fun readItem(el: JsonElement): Pair<String, Long>? {
        val o = el as? JsonObject ?: return null
        val console = o.string("consoleId")?.takeIf { it.isNotBlank() && !it.contains('|') } ?: return null
        val file = o.string("fileName")?.takeIf { it.isNotBlank() } ?: return null
        return DeviceSyncMerge.itemKey(console, file) to (o.long("addedAt") ?: 0L)
    }

    private fun JsonObject.array(key: String): List<JsonElement> = (get(key) as? JsonArray)?.toList().orEmpty()

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.asString

    private fun JsonObject.long(key: String): Long? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isNumber }?.let { runCatching { it.asLong }.getOrNull() }
}
