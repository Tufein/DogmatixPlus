package com.cortinadev.dogmatix.util

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/** A wish on the shared list: who added it, and who found it ([doneBy], empty while nobody did). */
data class SharedWish(
    val title: String,
    val consoleId: String?,
    val addedAt: Long,
    val addedBy: String,
    val doneBy: String = "",
    val doneAt: Long = 0L
) {
    val key: String get() = DeviceSyncMerge.wishKey(title, consoleId)
    fun toSync(): SyncWish = SyncWish(title, consoleId, addedAt)
}

/** The shared file: the wishes, the keys removed lately (with when), and who wrote it last. */
data class SharedWishlist(
    val wishes: Map<String, SharedWish> = emptyMap(),
    val removed: Map<String, Long> = emptyMap(),
    val updatedAt: Long = 0L,
    val updatedBy: String = "",
    val rev: String = ""
)

/** What this device remembers about the shared list besides the wishes themselves (see `SharedWishlistService`). */
data class SharedMeta(
    /** Wish key → name of who added it, for wishes added by somebody else. */
    val authors: Map<String, String> = emptyMap(),
    /** Wish key → name of who found it. */
    val doneBy: Map<String, String> = emptyMap()
) {
    companion object { val EMPTY = SharedMeta() }
}

/**
 * `<root>/shared/<list>/wishlist.json`, by hand with fixed field names (like [DeviceSyncJson]):
 * ```
 * { "format": "dogmatix-shared-wishlist", "version": 1, "updatedAt": 1759…, "updatedBy": "Anna", "rev": "…",
 *   "wishes":  [ { "title": "Mother 3", "consoleId": "gba", "addedAt": 1759…, "addedBy": "Anna", "doneBy": "Ben", "doneAt": 1759… } ],
 *   "removed": [ { "key": "gba|mother3", "at": 1759… } ] }
 * ```
 * Reading skips malformed rows and reports a newer format so it is never overwritten. Pure JVM for the tests.
 */
object SharedWishlistJson {
    const val FORMAT = "dogmatix-shared-wishlist"
    const val VERSION = 1
    const val META_FORMAT = "dogmatix-shared-wishlist-meta"

    sealed class Parsed {
        data class Ok(val list: SharedWishlist) : Parsed()
        data class Newer(val version: Int) : Parsed()
        object Invalid : Parsed()
    }

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    fun write(list: SharedWishlist): String = gson.toJson(JsonObject().apply {
        addProperty("format", FORMAT)
        addProperty("version", VERSION)
        addProperty("updatedAt", list.updatedAt)
        addProperty("updatedBy", list.updatedBy)
        if (list.rev.isNotEmpty()) addProperty("rev", list.rev)
        add("wishes", JsonArray().apply {
            list.wishes.values.forEach { w ->
                add(JsonObject().apply {
                    addProperty("title", w.title)
                    w.consoleId?.let { addProperty("consoleId", it) }
                    addProperty("addedAt", w.addedAt)
                    addProperty("addedBy", w.addedBy)
                    if (w.doneBy.isNotEmpty()) {
                        addProperty("doneBy", w.doneBy)
                        addProperty("doneAt", w.doneAt)
                    }
                })
            }
        })
        add("removed", JsonArray().apply {
            list.removed.forEach { (key, at) -> add(JsonObject().apply { addProperty("key", key); addProperty("at", at) }) }
        })
    })

    fun read(text: String): Parsed {
        val root = runCatching { JsonParser.parseString(text) }.getOrNull() as? JsonObject ?: return Parsed.Invalid
        if (root.string("format") != FORMAT) return Parsed.Invalid
        val version = root.long("version")?.toInt() ?: return Parsed.Invalid
        if (version > VERSION) return Parsed.Newer(version)
        val wishes = LinkedHashMap<String, SharedWish>()
        root.array("wishes").forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val title = o.string("title")?.trim()?.takeIf { it.length >= 2 } ?: return@forEach
            val wish = SharedWish(
                title = title,
                consoleId = o.string("consoleId")?.trim()?.takeIf { it.isNotEmpty() },
                addedAt = o.long("addedAt") ?: 0L,
                addedBy = o.string("addedBy")?.trim().orEmpty().take(MAX_NAME),
                doneBy = o.string("doneBy")?.trim().orEmpty().take(MAX_NAME),
                doneAt = o.long("doneAt") ?: 0L
            )
            val known = wishes[wish.key]
            // The same wish twice (two writers): the older one, and a "found" mark survives.
            wishes[wish.key] = if (known == null) wish else older(known, wish)
        }
        val removed = LinkedHashMap<String, Long>()
        root.array("removed").forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val key = o.string("key")?.takeIf { it.contains('|') } ?: return@forEach
            removed[key] = maxOf(o.long("at") ?: 0L, removed[key] ?: 0L)
        }
        return Parsed.Ok(SharedWishlist(wishes, removed, root.long("updatedAt") ?: 0L, root.string("updatedBy").orEmpty(), root.string("rev").orEmpty()))
    }

    private fun older(a: SharedWish, b: SharedWish): SharedWish {
        val first = if (b.addedAt < a.addedAt) b else a
        val done = listOf(a, b).filter { it.doneBy.isNotEmpty() }.minByOrNull { it.doneAt }
        return if (done == null) first else first.copy(doneBy = done.doneBy, doneAt = done.doneAt)
    }

    fun writeMeta(meta: SharedMeta): String = gson.toJson(JsonObject().apply {
        addProperty("format", META_FORMAT)
        add("authors", JsonObject().apply { meta.authors.forEach { (k, v) -> addProperty(k, v) } })
        add("doneBy", JsonObject().apply { meta.doneBy.forEach { (k, v) -> addProperty(k, v) } })
    })

    fun readMeta(text: String): SharedMeta {
        val root = runCatching { JsonParser.parseString(text) }.getOrNull() as? JsonObject ?: return SharedMeta.EMPTY
        if (root.string("format") != META_FORMAT) return SharedMeta.EMPTY
        fun names(key: String): Map<String, String> =
            (root.get(key) as? JsonObject)?.entrySet()?.mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.takeIf { it.isString }?.asString?.takeIf { it.isNotBlank() }?.let { k to it }
            }?.toMap().orEmpty()
        return SharedMeta(names("authors"), names("doneBy"))
    }

    /** Longest name of a person kept in the file. */
    const val MAX_NAME = 40

    private fun JsonObject.array(key: String): List<JsonElement> = (get(key) as? JsonArray)?.toList().orEmpty()
    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.asString
    private fun JsonObject.long(key: String): Long? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isNumber }?.let { runCatching { it.asLong }.getOrNull() }
}

/**
 * Three-way merge of the shared wishlist: this device's wishes ([local]), the file ([remote]) and the
 * wishes of this device's last sync ([base]). The wishes follow exactly the rules of device sync
 * ([DeviceSyncMerge]: on both = kept, new on one side = added, gone on one side but in the base =
 * removed unless re-added later, no base = union). On top of it:
 * - removals leave a **tombstone** (key + time) in the file, so a handhelds that never synced this
 *   list does not bring back what somebody deleted; a wish added again later (newer time) beats it;
 *   tombstones older than [TOMBSTONE_KEEP_MS] are dropped;
 * - who added a wish stays what the file says; a "found" mark ([SharedWish.doneBy]) is kept once
 *   anybody set it, the earliest one winning.
 * Pure JVM for the tests.
 */
object SharedWishlistMerge {

    const val TOMBSTONE_KEEP_MS = 90L * 24 * 3_600_000

    data class Result(
        /** The wishes the file and every device end up with. */
        val merged: Map<String, SharedWish>,
        val tombstones: Map<String, Long>,
        /** Wishes this device does not have yet. */
        val toLocalAdded: Map<String, SharedWish>,
        /** Keys this device must drop. */
        val toLocalRemoved: Set<String>,
        /** Keys that leave the file's wish list by this merge. */
        val removedFromRemote: Set<String>,
        /** The file must be written (it is new, or the merge changes it). */
        val remoteChanged: Boolean
    ) {
        val removals: Int get() = toLocalRemoved.size + removedFromRemote.size
        val additionsLocal: Int get() = toLocalAdded.size
    }

    fun merge(base: Map<String, SyncWish>?, local: Map<String, SharedWish>, remote: SharedWishlist?, now: Long): Result {
        val remoteWishes = remote?.wishes.orEmpty()
        val merged3 = DeviceSyncMerge.merge(
            base?.let { SyncLibrary(wishlist = it) },
            SyncLibrary(wishlist = local.mapValues { it.value.toSync() }),
            SyncLibrary(wishlist = remoteWishes.mapValues { it.value.toSync() })
        ).merged.wishlist

        val oldTombstones = remote?.removed.orEmpty().filterValues { now - it < TOMBSTONE_KEEP_MS }
        val alive = merged3.filter { (key, wish) -> oldTombstones[key]?.let { it >= wish.addedAt } != true }

        val merged = LinkedHashMap<String, SharedWish>()
        for ((key, sync) in alive) {
            val l = local[key]
            val r = remoteWishes[key]
            val done = listOfNotNull(l, r).filter { it.doneBy.isNotEmpty() }.minByOrNull { it.doneAt }
            merged[key] = SharedWish(
                title = sync.title,
                consoleId = sync.consoleId,
                addedAt = sync.addedAt,
                addedBy = r?.addedBy?.takeIf { it.isNotEmpty() } ?: l?.addedBy.orEmpty(),
                doneBy = done?.doneBy.orEmpty(),
                doneAt = done?.doneAt ?: 0L
            )
        }
        val removedFromRemote = remoteWishes.keys.filterTo(LinkedHashSet()) { it !in merged }
        val tombstones = LinkedHashMap<String, Long>()
        oldTombstones.forEach { (key, at) -> if (merged[key]?.let { it.addedAt > at } != true) tombstones[key] = at }
        removedFromRemote.forEach { key -> tombstones[key] = maxOf(now, tombstones[key] ?: 0L) }

        return Result(
            merged = merged,
            tombstones = tombstones,
            toLocalAdded = merged.filterKeys { it !in local },
            toLocalRemoved = local.keys.filterTo(LinkedHashSet()) { it !in merged },
            removedFromRemote = removedFromRemote,
            remoteChanged = remote == null || merged != remoteWishes || tombstones != remote.removed
        )
    }

    /** Same guard as device sync: removing more than an unusual amount at once waits for the user. */
    fun tooManyRemovals(result: Result, base: Map<String, SyncWish>?): Boolean =
        base != null && result.removals > maxOf(DeviceSyncMerge.GUARD_MIN, base.size / 4)

    /** The authors and "found by" notes for [merged]: authors other than [me] only. */
    fun metaOf(merged: Map<String, SharedWish>, me: String): SharedMeta = SharedMeta(
        authors = merged.filterValues { it.addedBy.isNotEmpty() && !it.addedBy.equals(me, ignoreCase = true) }.mapValues { it.value.addedBy },
        doneBy = merged.filterValues { it.doneBy.isNotEmpty() }.mapValues { it.value.doneBy }
    )
}
