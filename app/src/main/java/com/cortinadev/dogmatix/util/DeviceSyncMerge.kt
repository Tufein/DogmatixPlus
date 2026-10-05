package com.cortinadev.dogmatix.util

/** A wanted game as device sync carries it (the local id and "found" state stay on each device). */
data class SyncWish(val title: String, val consoleId: String?, val addedAt: Long)

/** A collection and its games (`consoleId|fileName` → when added). */
data class SyncCollection(val name: String, val createdAt: Long, val items: Map<String, Long>)

/**
 * What device sync keeps in step between handhelds. Every map is keyed so the same thing on two
 * devices has the same key: favourites and collection games by `consoleId|fileName` (like
 * [com.cortinadev.dogmatix.data.local.entity.FavouriteEntity.key]), wishes by [DeviceSyncMerge.wishKey],
 * collections by their name in lower case (names are unique per device, ignoring case).
 */
data class SyncLibrary(
    val favourites: Map<String, Long> = emptyMap(),
    val wishlist: Map<String, SyncWish> = emptyMap(),
    val collections: Map<String, SyncCollection> = emptyMap()
) {
    /** Number of things in it: favourites, wishes, collections and the games in them. */
    val size: Int get() = favourites.size + wishlist.size + collections.size + collections.values.sumOf { it.items.size }

    companion object { val EMPTY = SyncLibrary() }
}

/**
 * Three-way merge of the synced library: this device ([local]), the shared copy on the server
 * ([remote]) and the state both had at this device's last successful sync ([base]).
 *
 * For every thing (a favourite, a wish, a collection, a game in a collection):
 * - on both sides → kept;
 * - on one side only and not in the base → added there since the last sync → kept;
 * - on one side only and in the base → the other side removed it → removed, **unless** the side
 *   that has it added it again after the base (its time is newer than the base's), which wins;
 * - a collection one side deleted while the other side added games to it is kept (an edit beats
 *   a delete, so no newly added game is lost).
 *
 * Without a base (first sync, or the base belongs to another server/folder) nothing is removed:
 * the result is the union. When both sides have a thing with different times the newer time is
 * kept, so both sides' times are never newer than the base's afterwards (no false "added again").
 *
 * The caller applies [Result.toLocal] to this device, uploads [Result.merged] when
 * [Result.remoteChanged], then stores [Result.merged] as the new base. [tooManyRemovals] holds
 * back a sync that would remove an unusual amount at once (an emptied database, a replaced
 * server file) until the user confirms. Pure JVM for the tests.
 */
object DeviceSyncMerge {

    /** Fewer removals than this in one sync are never held back. */
    const val GUARD_MIN = 10

    /** What changes going from one library to another. */
    data class Diff(
        val favouritesAdded: Map<String, Long> = emptyMap(),
        val favouritesRemoved: Set<String> = emptySet(),
        val wishesAdded: Map<String, SyncWish> = emptyMap(),
        val wishesRemoved: Set<String> = emptySet(),
        /** Whole collections that are new, with their games. */
        val collectionsAdded: Map<String, SyncCollection> = emptyMap(),
        /** Whole collections that go (counted as one removal each, not per game). */
        val collectionsRemoved: Set<String> = emptySet(),
        /** Games added to collections that exist on both sides, per collection key. */
        val itemsAdded: Map<String, Map<String, Long>> = emptyMap(),
        val itemsRemoved: Map<String, Set<String>> = emptyMap()
    ) {
        val additions: Int get() = favouritesAdded.size + wishesAdded.size + collectionsAdded.size + itemsAdded.values.sumOf { it.size }
        val removals: Int get() = favouritesRemoved.size + wishesRemoved.size + collectionsRemoved.size + itemsRemoved.values.sumOf { it.size }
        val isEmpty: Boolean get() = additions == 0 && removals == 0
    }

    data class Result(
        val merged: SyncLibrary,
        /** What this device must change to match [merged]. */
        val toLocal: Diff,
        /** What the server's copy changes by (empty = no upload needed). */
        val toRemote: Diff
    ) {
        val remoteChanged: Boolean get() = !toRemote.isEmpty
        val removals: Int get() = toLocal.removals + toRemote.removals
    }

    /** Key of a wish: console (or `*` for any) + the title squashed like the library search. */
    fun wishKey(title: String, consoleId: String?): String {
        val squashed = SearchNormalizer.key(title).ifEmpty { title.trim().lowercase() }
        return (consoleId?.takeIf { it.isNotBlank() } ?: "*") + "|" + squashed
    }

    /** Key of a favourite or a game in a collection. */
    fun itemKey(consoleId: String, fileName: String): String = "$consoleId|$fileName"

    /** (consoleId, fileName) of an [itemKey]; null when malformed. */
    fun splitItemKey(key: String): Pair<String, String>? {
        val i = key.indexOf('|')
        if (i <= 0 || i == key.lastIndex) return null
        return key.substring(0, i) to key.substring(i + 1)
    }

    /** Key of a collection name. */
    fun collectionKey(name: String): String = name.trim().lowercase()

    fun merge(base: SyncLibrary?, local: SyncLibrary, remote: SyncLibrary): Result {
        val favourites = merge3(base?.favourites, local.favourites, remote.favourites, { it }) { l, r -> maxOf(l, r) }
        val wishlist = merge3(base?.wishlist, local.wishlist, remote.wishlist, { it.addedAt }) { l, r -> l.copy(addedAt = maxOf(l.addedAt, r.addedAt)) }
        val collections = LinkedHashMap<String, SyncCollection>()
        for (key in (local.collections.keys + remote.collections.keys)) {
            val l = local.collections[key]
            val r = remote.collections[key]
            val b = base?.collections?.get(key)
            val kept = when {
                l != null && r != null -> SyncCollection(
                    name = l.name,
                    createdAt = maxOf(l.createdAt, r.createdAt),
                    items = merge3(b?.items, l.items, r.items, { it }) { x, y -> maxOf(x, y) }
                )
                l != null -> l.takeIf { b == null || l.createdAt > b.createdAt || addedSince(l.items, b.items) }
                r != null -> r.takeIf { b == null || r.createdAt > b.createdAt || addedSince(r.items, b.items) }
                else -> null
            }
            if (kept != null) collections[key] = kept
        }
        val merged = SyncLibrary(favourites, wishlist, collections)
        return Result(merged, diff(local, merged), diff(remote, merged))
    }

    /** The changes that turn [from] into [to]. */
    fun diff(from: SyncLibrary, to: SyncLibrary): Diff {
        val itemsAdded = LinkedHashMap<String, Map<String, Long>>()
        val itemsRemoved = LinkedHashMap<String, Set<String>>()
        for ((key, target) in to.collections) {
            val source = from.collections[key] ?: continue
            val added = target.items.filterKeys { it !in source.items }
            val removed = source.items.keys.filterTo(LinkedHashSet()) { it !in target.items }
            if (added.isNotEmpty()) itemsAdded[key] = added
            if (removed.isNotEmpty()) itemsRemoved[key] = removed
        }
        return Diff(
            favouritesAdded = to.favourites.filterKeys { it !in from.favourites },
            favouritesRemoved = from.favourites.keys.filterTo(LinkedHashSet()) { it !in to.favourites },
            wishesAdded = to.wishlist.filterKeys { it !in from.wishlist },
            wishesRemoved = from.wishlist.keys.filterTo(LinkedHashSet()) { it !in to.wishlist },
            collectionsAdded = to.collections.filterKeys { it !in from.collections },
            collectionsRemoved = from.collections.keys.filterTo(LinkedHashSet()) { it !in to.collections },
            itemsAdded = itemsAdded,
            itemsRemoved = itemsRemoved
        )
    }

    /** [library] with [diff] applied (what a device's database does with [Result.toLocal]). */
    fun applyTo(library: SyncLibrary, diff: Diff): SyncLibrary {
        val collections = LinkedHashMap(library.collections)
        diff.collectionsRemoved.forEach { collections.remove(it) }
        collections.putAll(diff.collectionsAdded)
        diff.itemsRemoved.forEach { (key, items) -> collections[key]?.let { c -> collections[key] = c.copy(items = c.items - items) } }
        diff.itemsAdded.forEach { (key, items) -> collections[key]?.let { c -> collections[key] = c.copy(items = c.items + items) } }
        return SyncLibrary(
            favourites = library.favourites - diff.favouritesRemoved + diff.favouritesAdded,
            wishlist = library.wishlist - diff.wishesRemoved + diff.wishesAdded,
            collections = collections
        )
    }

    /**
     * Whether [local] changed since [base] in a way a sync must send: something added or removed,
     * or something removed and added again (a newer time). Always true without a base.
     */
    fun hasLocalChanges(base: SyncLibrary?, local: SyncLibrary): Boolean {
        if (base == null) return true
        if (!diff(base, local).isEmpty) return true
        if (local.favourites.any { (k, t) -> t > (base.favourites[k] ?: Long.MAX_VALUE) }) return true
        if (local.wishlist.any { (k, w) -> w.addedAt > (base.wishlist[k]?.addedAt ?: Long.MAX_VALUE) }) return true
        return local.collections.any { (k, c) ->
            val b = base.collections[k] ?: return@any false
            c.createdAt > b.createdAt || c.items.any { (i, t) -> t > (b.items[i] ?: Long.MAX_VALUE) }
        }
    }

    /**
     * A sync that would remove more than [GUARD_MIN] things and more than a quarter of what the
     * base held is held back until the user confirms. Never without a base (nothing is removed then).
     */
    fun tooManyRemovals(result: Result, base: SyncLibrary?): Boolean =
        base != null && result.removals > maxOf(GUARD_MIN, base.size / 4)

    /** Generic three-way merge of keyed things with a time; see the class comment for the rules. */
    private fun <V> merge3(
        base: Map<String, V>?,
        local: Map<String, V>,
        remote: Map<String, V>,
        time: (V) -> Long,
        both: (V, V) -> V
    ): Map<String, V> {
        val out = LinkedHashMap<String, V>()
        for (key in (local.keys + remote.keys)) {
            val l = local[key]
            val r = remote[key]
            val b = base?.get(key)
            val value: V? = when {
                l != null && r != null -> both(l, r)
                l != null -> l.takeIf { b == null || time(l) > time(b) }
                r != null -> r.takeIf { b == null || time(r) > time(b) }
                else -> null
            }
            if (value != null) out[key] = value
        }
        return out
    }

    /** A side added (or re-added) games to a collection since the base. */
    private fun addedSince(items: Map<String, Long>, baseItems: Map<String, Long>): Boolean =
        items.any { (key, time) -> baseItems[key]?.let { time > it } ?: true }
}
