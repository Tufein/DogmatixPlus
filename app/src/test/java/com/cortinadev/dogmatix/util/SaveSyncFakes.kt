package com.cortinadev.dogmatix.util

import java.io.IOException
import java.time.Instant

/** A test clock: every call moves on by a second, like real edits that are seconds apart. */
class TestClock(var now: Long = 1_700_000_000_000L) {
    fun tick(): Long { now += 1_000; return now }
}

/** A device's picked saves / states folders, in memory. */
class FakeSaveStore(private val clock: TestClock, private val kinds: Set<SaveKind> = setOf(SaveKind.SAVE, SaveKind.STATE)) : SaveStore {
    class File(var bytes: ByteArray, var modified: Long)
    val files = mutableMapOf<Pair<SaveKind, String>, File>()
    val folders = mutableMapOf<SaveKind, MutableSet<String>>()
    val backups = mutableListOf<String>()

    fun put(kind: SaveKind, path: String, content: String) {
        files[kind to path] = File(content.toByteArray(), clock.tick())
        if (path.contains('/')) folders.getOrPut(kind) { mutableSetOf() } += path.substringBefore('/')
    }
    fun text(kind: SaveKind, path: String): String? = files[kind to path]?.bytes?.toString(Charsets.UTF_8)

    override suspend fun list() = SaveStore.Listing(
        files.map { (key, f) -> LocalSaveFile(key.first, key.second, f.bytes.size.toLong(), f.modified) },
        kinds.associateWith { folders[it].orEmpty().toSet() }
    )
    override suspend fun read(file: LocalSaveFile) = files[file.kind to file.path]?.bytes ?: throw IOException("gone")
    override suspend fun write(kind: SaveKind, path: String, bytes: ByteArray): LocalSaveFile {
        val f = File(bytes, clock.tick())
        files[kind to path] = f
        return LocalSaveFile(kind, path, bytes.size.toLong(), f.modified)
    }
    override suspend fun backup(file: LocalSaveFile) { backups += file.path }
    override suspend fun delete(file: LocalSaveFile) { files.remove(file.kind to file.path) }
    /** The user deletes a save on this device (no clock tick: nothing was written). */
    fun remove(kind: SaveKind, path: String) { files.remove(kind to path) }
}

/** A RomM server in memory: upserts by game + name, lists times to the second like RomM does. */
class FakeSaveServer(private val clock: TestClock, private val roms: List<SaveSyncPlanner.RomCandidate>) : SaveServer {
    class Stored(val id: Int, val kind: SaveKind, val romId: Int, val name: String, var emulator: String?, var bytes: ByteArray, var updated: Long)
    val stored = mutableListOf<Stored>()
    var searches = 0
    private var nextId = 1

    private fun Stored.remote(precise: Boolean) = RemoteSaveFile(
        kind, id, romId, name, emulator,
        if (precise) Instant.ofEpochMilli(updated + 123).toString() else Instant.ofEpochMilli(updated).toString(),
        bytes.size.toLong(), "/api/${kind.apiPath}/$id/content",
        contentHash = if (kind == SaveKind.SAVE) SaveSyncEngine.md5(bytes) else null
    )

    fun text(kind: SaveKind, name: String) = stored.firstOrNull { it.kind == kind && it.name == name }?.bytes?.toString(Charsets.UTF_8)

    /** Another device (or RomM's web player) saving. */
    fun put(kind: SaveKind, romId: Int, name: String, content: String, emulator: String? = null) {
        val existing = stored.firstOrNull { it.kind == kind && it.romId == romId && it.name == name }
        if (existing != null) { existing.bytes = content.toByteArray(); existing.updated = clock.tick() }
        else stored += Stored(nextId++, kind, romId, name, emulator, content.toByteArray(), clock.tick())
    }

    override suspend fun list(kind: SaveKind) = stored.filter { it.kind == kind }.map { it.remote(precise = false) }
    override suspend fun download(save: RemoteSaveFile) = stored.first { it.id == save.id && it.kind == save.kind }.bytes
    override suspend fun upload(kind: SaveKind, romId: Int, fileName: String, emulator: String?, bytes: ByteArray): RemoteSaveFile {
        put(kind, romId, fileName, bytes.toString(Charsets.UTF_8), emulator)
        return stored.first { it.kind == kind && it.romId == romId && it.name == fileName }.also { it.emulator = emulator }.remote(precise = true)
    }
    override suspend fun delete(save: RemoteSaveFile) { stored.removeAll { it.id == save.id && it.kind == save.kind } }
    /** Deleted in RomM's web interface. */
    fun remove(kind: SaveKind, name: String) { stored.removeAll { it.kind == kind && it.name == name } }
    override suspend fun searchRoms(term: String): List<SaveSyncPlanner.RomCandidate> {
        searches++
        val words = term.lowercase().split(' ').filter { it.isNotBlank() }
        return roms.filter { rom -> words.all { rom.fsName.lowercase().contains(it) } }
    }
}
