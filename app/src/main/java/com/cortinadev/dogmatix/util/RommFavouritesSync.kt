package com.cortinadev.dogmatix.util

/**
 * Keeps the stars in the app and the hearts in RomM's favourites collection in step (5.0,
 * Settings → RomM → "Keep favourites in step with RomM"). A three-way merge on RomM rom ids
 * against the set both sides agreed on at the last sync (the *base*):
 *  - a game starred here and not hearted on RomM is hearted when it was not in the base (it is
 *    new here), and loses its star when it was (RomM took the heart away);
 *  - a game hearted on RomM and not starred here is starred when it was not in the base, and
 *    loses its heart when it was (the star was taken away here);
 *  - the first sync (no base) only adds, on both sides.
 * Only games this device can place ([known]: a library row or a star of it) take part; hearts of
 * other games stay on RomM untouched.
 */
object RommFavouritesSync {

    /**
     * @param remote the new content of RomM's favourites collection
     * @param addLocal rom ids to star here; [removeLocal] rom ids to un-star here
     * @param base the agreement to remember for the next sync
     * @param pushNeeded [remote] differs from what the server holds
     */
    data class Plan(
        val remote: Set<Int>,
        val addLocal: Set<Int>,
        val removeLocal: Set<Int>,
        val base: Set<Int>,
        val pushNeeded: Boolean,
        /** The base was set aside (first sync, or a suspicious empty collection): nothing was removed. */
        val additive: Boolean
    )

    /**
     * @param local rom ids of the starred games that are on the server
     * @param remote rom ids in RomM's favourites collection now
     * @param base the agreed set of the last sync; null = never synced (or another collection)
     * @param known rom ids whose star state this device knows (always includes [local])
     */
    fun plan(local: Set<Int>, remote: Set<Int>, base: Set<Int>?, known: Set<Int>): Plan {
        val knownAll = known + local
        val remoteKnown = remote intersect knownAll
        // An empty (or unreadable) collection, or no stars at all, where several agreed favourites
        // used to be looks like an accident (a wiped server, a restored device), not like the user
        // taking every one away: merge instead of removing.
        val agreedKnown = base.orEmpty() intersect knownAll
        val suspicious = base != null && agreedKnown.size >= MASS_REMOVAL && (remoteKnown.isEmpty() || local.isEmpty())
        val agreed: Set<Int>? = if (suspicious) null else base
        val merged = HashSet<Int>()
        for (id in knownAll + remote) {
            val inL = id in local
            val inR = id in remote
            if (id !in knownAll) { if (inR) merged += id; continue }
            val keep = when {
                inL && inR -> true
                !inL && !inR -> false
                agreed == null -> true
                // Starred here without a heart: new here (keep), or RomM took the heart away (drop).
                // Hearted without a star: new on RomM (keep), or the star was taken away here (drop).
                else -> id !in agreed
            }
            if (keep) merged += id
        }
        val newBase = (merged intersect knownAll) + (base.orEmpty() - knownAll)
        return Plan(
            remote = merged,
            addLocal = (merged intersect knownAll) - local,
            removeLocal = local - merged,
            base = newBase,
            pushNeeded = merged != remote,
            additive = agreed == null
        )
    }

    /** The collection the hearts live in: a RomM favourites collection, else one named Favourites. */
    data class CollectionRef(val id: Int, val name: String, val userId: Int?, val isFavourite: Boolean)

    /**
     * Picks the account's favourites collection: the one RomM marks `is_favorite`, else one named
     * "Favourites" / "Favorites" (what older RomM web interfaces created). Other users' public
     * collections are never picked. null = none yet (it is created on the first heart).
     */
    fun pickCollection(collections: List<CollectionRef>, me: Int?): CollectionRef? {
        val mine = collections.filter { me == null || it.userId == null || it.userId == me }
        return mine.firstOrNull { it.isFavourite }
            ?: mine.firstOrNull { it.name.trim().lowercase() in FAVOURITE_NAMES }
    }

    /** A collection read as empty although the server counts ROMs in it cannot be trusted. */
    fun isReadable(romIds: Collection<Int>, romCount: Int?): Boolean = romCount == null || romCount <= romIds.size

    const val COLLECTION_NAME = "Favourites"
    private val FAVOURITE_NAMES = setOf("favourites", "favorites", "favoritos", "favoris", "favoriten", "preferiti", "favorieten")
    private const val MASS_REMOVAL = 3
}
