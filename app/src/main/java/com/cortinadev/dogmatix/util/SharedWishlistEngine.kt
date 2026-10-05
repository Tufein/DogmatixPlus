package com.cortinadev.dogmatix.util

import java.util.UUID

/**
 * One sync of the shared family wishlist against `<root>/shared/<list>/wishlist.json` on a
 * [DavStore]: the same careful read, three-way merge ([SharedWishlistMerge]) and conditional write
 * ([DavConditionalWriter]) as [DeviceSyncEngine], for a file several people write. A server file
 * that is missing is a fresh start, a damaged or newer one is left alone (exceptions), a copy older
 * than this device's last sync (restored snapshot) is merged as a union, and a base of another login,
 * server or list is ignored. Pure JVM for the tests.
 */
class SharedWishlistEngine(
    private val store: DavStore,
    private val serverUrl: String,
    rootUrl: String,
    listName: String,
    private val local: Local,
    private val account: String = "",
    private val nonce: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** This device's side: its wishes, how to change them, and where the base is kept. */
    interface Local {
        /** The device's wishes by key; [SharedWish.addedBy] is who added it (the person's own name when it did), [SharedWish.doneBy] who found it. */
        suspend fun snapshot(): Map<String, SharedWish>
        /** Adds [added], drops [removed] (keys) in one go (all or nothing) and remembers [merged]'s authors and "found by" notes. */
        suspend fun apply(added: Collection<SharedWish>, removed: Set<String>, merged: Map<String, SharedWish>)
        suspend fun readBase(): DeviceSyncJson.Base?
        suspend fun writeBase(base: DeviceSyncJson.Base)
    }

    sealed class Outcome {
        data class Synced(val added: Int, val removed: Int, val sent: Boolean, val rolledBack: Boolean = false) : Outcome()
        /** Nothing done: [removals] wishes would go at once; sync again with `allowMassRemoval`. */
        data class HeldBack(val removals: Int) : Outcome()
    }

    class NewerFileException(val version: Int) : IllegalStateException("Shared wishlist format $version is newer than ${SharedWishlistJson.VERSION}")
    class UnreadableFileException : IllegalStateException("The shared wishlist file cannot be read")

    private val folderUrl = WebDavPaths.join(rootUrl, listOf(SHARED, folderName(listName) ?: DEFAULT_LIST), asCollection = true)
    val fileUrl: String = WebDavPaths.child(folderUrl, FILE_NAME)

    /** [me] is the name this person goes by (it is written as `addedBy` of what they add). */
    suspend fun sync(me: String, allowMassRemoval: Boolean = false): Outcome {
        val storedBase = local.readBase()?.takeIf { it.remoteUrl == fileUrl && (it.account.isEmpty() || it.account == account) }
        val snapshot = local.snapshot()
        val writer = DavConditionalWriter(store, fileUrl, CONTENT_TYPE, MAX_FILE_BYTES)
        while (true) {
            val file = store.get(fileUrl, MAX_FILE_BYTES)
            val remote = file?.takeIf { it.bytes.isNotEmpty() }?.let { readList(it.bytes) }
            val rolledBack = remote != null && storedBase != null && DeviceSyncEngine.isRollback(remote.updatedAt, storedBase.savedAt)
            val base = storedBase?.library?.wishlist?.takeIf { remote != null && !rolledBack }
            val now = clock()
            val result = SharedWishlistMerge.merge(base, snapshot, remote, now)
            if (!allowMassRemoval && SharedWishlistMerge.tooManyRemovals(result, base)) return Outcome.HeldBack(result.removals)

            var sent = false
            if (result.remoteChanged) {
                val rev = nonce()
                val text = SharedWishlistJson.write(
                    SharedWishlist(result.merged, result.tombstones, now, me, rev)
                ).toByteArray(Charsets.UTF_8)
                if (file == null) store.ensureCollection(folderUrl, WebDavPaths.normalizeServer(serverUrl) ?: folderUrl)
                if (!writer.attempt(file, text) { stored -> isOurs(stored, text, rev) }) continue
                sent = true
            }
            local.apply(result.toLocalAdded.values, result.toLocalRemoved, result.merged)
            local.writeBase(
                DeviceSyncJson.Base(fileUrl, SyncLibrary(wishlist = result.merged.mapValues { it.value.toSync() }), clock(), account)
            )
            return Outcome.Synced(result.toLocalAdded.size, result.toLocalRemoved.size, sent, rolledBack)
        }
    }

    private fun readList(bytes: ByteArray): SharedWishlist =
        when (val parsed = SharedWishlistJson.read(bytes.toString(Charsets.UTF_8))) {
            is SharedWishlistJson.Parsed.Ok -> parsed.list
            is SharedWishlistJson.Parsed.Newer -> throw NewerFileException(parsed.version)
            SharedWishlistJson.Parsed.Invalid -> throw UnreadableFileException()
        }

    private fun isOurs(stored: ByteArray, written: ByteArray, rev: String): Boolean {
        if (stored.contentEquals(written)) return true
        val parsed = SharedWishlistJson.read(stored.toString(Charsets.UTF_8))
        return parsed is SharedWishlistJson.Parsed.Ok && parsed.list.rev == rev
    }

    companion object {
        const val SHARED = "shared"
        const val FILE_NAME = "wishlist.json"
        const val DEFAULT_LIST = "wishlist"
        const val CONTENT_TYPE = "application/json; charset=utf-8"
        const val MAX_FILE_BYTES = 4L * 1024 * 1024
        const val MAX_LIST_NAME = 60

        /**
         * The folder name of a list: trimmed, lower case (so "Family" and "family" are one list),
         * path separators and dots-only names made harmless, at most [MAX_LIST_NAME] characters.
         * Null for an empty name (the feature is off).
         */
        fun folderName(listName: String): String? {
            val clean = listName.trim().replace(Regex("[/\\\\]+"), "-").replace(Regex("\\s+"), " ").take(MAX_LIST_NAME).trim()
                .lowercase()
            if (clean.isEmpty() || clean.all { it == '.' }) return null
            return clean
        }
    }
}
