package com.tufein.valkyr.util

/** How a save sync treats saves that would be replaced (Settings → Save sync → Backups and safety). */
data class SaveSyncOptions(
    /** A save that differs on both sides and is newer on the server is taken from the server (the device copy is backed up first). */
    val preferNewerServer: Boolean = false,
    /** Nothing that exists is replaced or deleted by a sync: only new saves are added. */
    val nonDestructive: Boolean = false
)

/**
 * The three-way merge of the save sync, pure so it can be tested to the bone.
 *
 * The local manifest ([SaveSyncRecord]) remembers what both sides held after the last sync, including
 * the MD5 of the bytes ([SaveSyncRecord.baseHash]). Against that base:
 * - a side whose bytes still equal the base did not change, whatever its file time says (an emulator
 *   that rewrites an identical save, a server that only touches its timestamp);
 * - a change on one side only is applied to the other;
 * - a change on both sides is a conflict that nothing touches until the user chooses, unless both
 *   sides now hold the same bytes (then it is simply recorded).
 *
 * Without a base hash (records of earlier versions, states on servers that give no hash) the sides are
 * judged by size and time, as before.
 */
object SaveMerge {

    /** What the merge says about one file. */
    enum class Verdict { UNCHANGED, PUSH, PULL, CONVERGED, CONFLICT }

    fun sameHash(a: String?, b: String?): Boolean = a != null && b != null && a.equals(b, ignoreCase = true)

    /**
     * Whether the device file changed since the last sync. [localHash] is the MD5 of its bytes, read
     * only for files whose size or time differ from the record ([needsHash]); null when it was not read.
     */
    fun localChanged(local: LocalSaveFile, record: SaveSyncRecord, localHash: String?): Boolean {
        if (!SaveSyncPlanner.localChanged(local, record)) return false
        val base = record.baseHash
        return if (base != null && localHash != null) !sameHash(localHash, base) else true
    }

    /** Whether the server file changed since the last sync: a new time or size with the same bytes as the base is no change. */
    fun remoteChanged(remote: RemoteSaveFile, record: SaveSyncRecord): Boolean {
        if (!SaveSyncPlanner.remoteChanged(remote, record)) return false
        val base = record.baseHash
        return if (base != null && remote.contentHash != null && sameHash(remote.contentHash, base)) false else true
    }

    /** Device files whose bytes are worth reading before the plan: their size or time differ from the record. */
    fun needsHash(local: LocalSaveFile, record: SaveSyncRecord?): Boolean =
        record != null && SaveSyncPlanner.localChanged(local, record)

    /** The three-way verdict from the three hashes; [localTouched] / [remoteTouched] are the size-and-time fallbacks. */
    fun verdict(base: String?, local: String?, remote: String?, localTouched: Boolean, remoteTouched: Boolean): Verdict {
        if (sameHash(local, remote)) return if (sameHash(local, base)) Verdict.UNCHANGED else Verdict.CONVERGED
        val localChanged = if (base != null && local != null) !sameHash(local, base) else localTouched
        val remoteChanged = if (base != null && remote != null) !sameHash(remote, base) else remoteTouched
        return when {
            localChanged && remoteChanged -> Verdict.CONFLICT
            localChanged -> Verdict.PUSH
            remoteChanged -> Verdict.PULL
            else -> Verdict.UNCHANGED
        }
    }

    // ---- What the options make of each case ----------------------------------------------------

    /** What to do with a file that differs on both sides. */
    enum class OnDiffer { ASK, TAKE_SERVER, KEEP_BOTH }

    /**
     * A file that differs on both sides (a conflict, or two never-synced files with different bytes).
     * "Prefer the newer server save" takes the server's when its time is clearly the later one; a card or
     * bundle that holds several games' saves is always left to the user. In non-destructive mode "take"
     * becomes "keep both": the server version is set aside as a copy and nothing is replaced.
     */
    fun onDiffer(local: LocalSaveFile, remote: RemoteSaveFile, options: SaveSyncOptions): OnDiffer {
        if (!options.preferNewerServer || EmulatorSaveLayouts.isChosenGameOnly(local)) return OnDiffer.ASK
        if (SaveConflictInfo.of(local, remote).newer != NewerSide.SERVER) return OnDiffer.ASK
        return if (options.nonDestructive) OnDiffer.KEEP_BOTH else OnDiffer.TAKE_SERVER
    }

    /** Only the server changed: the device file is replaced, or in non-destructive mode the server version is kept as a copy. */
    fun replacesDevice(options: SaveSyncOptions): Boolean = !options.nonDestructive

    /** Only the device changed: the server save is replaced, or in non-destructive mode left as it is. */
    fun replacesServer(options: SaveSyncOptions): Boolean = !options.nonDestructive

    /** Deletions are mirrored only when asked for and never in non-destructive mode. */
    fun mirrorsDeletions(syncDeletions: Boolean, options: SaveSyncOptions): Boolean = syncDeletions && !options.nonDestructive

    // ---- Keeping both ---------------------------------------------------------------------------

    /**
     * The record after the user kept both copies: the server version is set aside as a safety copy and
     * both sides are noted as seen, so the next sync neither reports the file again nor replaces either
     * copy until one of them changes (the server version is then not lost: its copy is kept).
     */
    fun keepBothRecord(local: LocalSaveFile, remote: RemoteSaveFile): SaveSyncRecord =
        SaveSyncRecord(
            kind = local.kind, path = local.path, romId = remote.romId, remoteId = remote.id, remoteUpdatedAt = remote.updatedAt,
            localSize = local.size, localModified = local.modified, remoteSize = remote.size, remoteHash = remote.contentHash,
            baseHash = null
        )

    /** The record with the server and device snapshots brought up to date, keeping its base (a time that moved, bytes that did not). */
    fun refreshed(record: SaveSyncRecord, local: LocalSaveFile, remote: RemoteSaveFile): SaveSyncRecord =
        record.copy(
            remoteId = remote.id, remoteUpdatedAt = remote.updatedAt, remoteSize = remote.size, remoteHash = remote.contentHash,
            localSize = local.size, localModified = local.modified
        )

    /** Whether [refreshed] would change anything (so an unchanged file does not rewrite the manifest). */
    fun needsRefresh(record: SaveSyncRecord, local: LocalSaveFile, remote: RemoteSaveFile): Boolean = record != refreshed(record, local, remote)

    /** Settings keys of this feature, for the settings backup ([SettingSchema]). */
    const val KEY_BACKUP_KEEP = "save_sync_backup_keep"
    const val KEY_NON_DESTRUCTIVE = "save_sync_non_destructive"
    const val KEY_PREFER_SERVER = "save_sync_prefer_server"

    val SETTING_TYPES: Map<String, String> = mapOf(KEY_BACKUP_KEEP to "i", KEY_NON_DESTRUCTIVE to "b", KEY_PREFER_SERVER to "b")
}
