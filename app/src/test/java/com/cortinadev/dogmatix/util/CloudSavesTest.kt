package com.cortinadev.dogmatix.util

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

class CloudSavesTest {

    private fun json(s: String) = JsonParser.parseString(s)

    /** RomM 3.10 / 4: a bare array, as `GET /api/saves?rom_id=` answers. */
    private val romm4Saves = """[
        {"id": 11, "rom_id": 42, "user_id": 1, "file_name": "Pokemon Emerald (USA).srm", "file_name_no_tags": "Pokemon Emerald",
         "file_extension": "srm", "file_path": "users/abc/saves/gba/42", "file_size_bytes": 131072,
         "full_path": "users/abc/saves/gba/42/Pokemon Emerald (USA).srm",
         "download_path": "/api/raw/assets/users/abc/saves/gba/42/Pokemon Emerald (USA).srm?timestamp=2026-10-03 12:00:00",
         "missing_from_fs": false, "created_at": "2026-09-01T10:00:00+00:00", "updated_at": "2026-10-03T12:00:00.123456+00:00",
         "emulator": "mGBA", "screenshot": null},
        {"id": 12, "rom_id": 42, "file_name": "Pokemon Emerald (USA).srm", "file_size_bytes": 131072,
         "download_path": "/api/raw/assets/x.srm", "updated_at": "2026-09-20T08:00:00+00:00", "emulator": "mGBA"},
        {"id": 99, "rom_id": 7, "file_name": "Other.srm", "updated_at": "2026-10-04T08:00:00+00:00"}
    ]"""

    /** RomM 5: paged `{items}`, slot saves, numbers as strings, a screenshot object and a device. */
    private val romm5States = """{"items": [
        {"id": "21", "rom_id": "42", "file_name": "Pokemon Emerald (USA).state1", "file_size_bytes": "524288",
         "download_path": "/api/states/21/content", "updated_at": "2026-10-05T09:30:00Z", "emulator": "gpsp",
         "screenshot": {"id": 5, "file_name": "Pokemon Emerald (USA).state1.png", "file_path": "users/abc/screenshots/gba/42",
                        "download_path": "/api/raw/assets/users/abc/screenshots/gba/42/Pokemon Emerald (USA).state1.png"},
         "device": {"id": "d1", "name": "Thor"}},
        {"id": 22, "rom_id": 42, "file_name": "Pokemon Emerald (USA) [2026-10-04 20-00-00].state", "slot": "2",
         "updated_at": 1791230400, "screenshot": {"file_path": "/users/abc/screenshots", "file_name": "s.png"}},
        {"id": 23, "rom_id": 42, "file_name": "gone.state", "missing_from_fs": "true", "updated_at": "2026-10-01T00:00:00"},
        {"rom_id": 42, "file_name": "no id.state"},
        {"id": 24, "file_name": "no rom.state"},
        {"id": 25, "rom_id": 42},
        "not an object",
        {"id": 26, "rom_id": 42, "file_name": "bad numbers.state", "file_size_bytes": "lots", "updated_at": {"weird": true}}
    ], "total": 8}"""

    @Test fun `romm 4 array of saves is read with times sizes and emulators`() {
        val saves = CloudSaves.parse(SaveKind.SAVE, json(romm4Saves))
        assertEquals(3, saves.size)
        val first = saves.first { it.id == 11 }
        assertEquals(42, first.romId)
        assertEquals("Pokemon Emerald (USA).srm", first.fileName)
        assertEquals(131072L, first.size)
        assertEquals("mGBA", first.emulator)
        assertEquals("mGBA", first.via)
        assertEquals(1791028800123L, first.updatedMillis)
        assertNull(first.screenshotPath)
        assertNull(first.slot)
        assertTrue(first.syncable)
    }

    @Test fun `romm 5 paged states keep slots screenshots devices and tolerate bad entries`() {
        val states = CloudSaves.parse(SaveKind.STATE, json(romm5States))
        assertEquals(listOf(21, 22, 23, 26), states.map { it.id })
        val s21 = states.first { it.id == 21 }
        assertEquals(42, s21.romId)
        assertEquals(524288L, s21.size)
        assertEquals("Thor", s21.device)
        assertEquals("Thor", s21.via)
        assertEquals("/api/raw/assets/users/abc/screenshots/gba/42/Pokemon Emerald (USA).state1.png", s21.screenshotPath)
        val slot = states.first { it.id == 22 }
        assertEquals("2", slot.slot)
        assertFalse(slot.syncable)
        assertEquals(1791230400000L, slot.updatedMillis)
        assertEquals("/api/raw/assets/users/abc/screenshots/s.png", slot.screenshotPath)
        assertTrue(states.first { it.id == 23 }.missingFromFs)
        val bad = states.first { it.id == 26 }
        assertEquals(0L, bad.size)
        assertNull(bad.updatedMillis)
    }

    @Test fun `anything that is not a listing gives nothing instead of failing`() {
        assertTrue(CloudSaves.parse(SaveKind.SAVE, null).isEmpty())
        assertTrue(CloudSaves.parse(SaveKind.SAVE, json("\"oops\"")).isEmpty())
        assertTrue(CloudSaves.parse(SaveKind.SAVE, json("{\"detail\": \"Not found\"}")).isEmpty())
        assertTrue(CloudSaves.parse(SaveKind.SAVE, json("42")).isEmpty())
        assertTrue(CloudSaves.parse(SaveKind.SAVE, json("{\"items\": null}")).isEmpty())
    }

    @Test fun `older servers' rom details carry the user's saves and states`() {
        val rom = json("""{"id": 42, "fs_name": "Pokemon Emerald (USA).gba", "platform_id": 3,
            "user_saves": [{"id": 1, "rom_id": 42, "file_name": "a.srm", "updated_at": "2026-10-01T00:00:00"}],
            "user_states": []}""").asJsonObject
        assertEquals(1, CloudSaves.parse(SaveKind.SAVE, rom.get("user_saves")).size)
        assertEquals(1, CloudSaves.parse(SaveKind.SAVE, rom).size)
        assertEquals("Pokemon Emerald (USA).gba" to 3, CloudSaves.romFileAndPlatform(rom))
        assertEquals("b.gba" to 9, CloudSaves.romFileAndPlatform(json("""{"file_name": "b.gba", "platform": {"id": "9"}}""")))
        assertNull(CloudSaves.romFileAndPlatform(json("""{"fs_name": "c.gba"}""")))
        assertNull(CloudSaves.romFileAndPlatform(json("[]")))
    }

    @Test fun `server paths become valid urls`() {
        val base = "https://romm.home:8443"
        assertEquals(
            "https://romm.home:8443/api/raw/assets/users/a/Pok%C3%A9mon%20Emerald%20(USA).png?timestamp=2026-10-03%2012:00:00",
            CloudSaves.absoluteUrl("$base/", "/api/raw/assets/users/a/Pokémon Emerald (USA).png?timestamp=2026-10-03 12:00:00")
        )
        assertEquals("$base/api/x/Game%20%2325%25.png", CloudSaves.absoluteUrl(base, "api/x/Game #25%.png"))
        assertEquals("$base/api/x/already%20encoded.png", CloudSaves.absoluteUrl(base, "/api/x/already%20encoded.png"))
        assertEquals("https://cdn.example/s%20s.png", CloudSaves.absoluteUrl(base, "https://cdn.example/s s.png"))
    }

    @Test fun `saves belong to the game named like them`() {
        val stem = CloudSaves.gameStem("Pokemon Emerald (USA).gba")
        assertEquals("Pokemon Emerald (USA)", stem)
        assertTrue(CloudSaves.belongsToGame("Pokemon Emerald (USA).srm", stem))
        assertTrue(CloudSaves.belongsToGame("pokemon emerald (usa).state.auto", stem))
        assertTrue(CloudSaves.belongsToGame("Pokemon Emerald (USA).state12", stem))
        assertFalse(CloudSaves.belongsToGame("Pokemon Ruby (USA).srm", stem))
        assertFalse(CloudSaves.belongsToGame("Pokemon Emerald (USA) Hack.srm", stem))
        assertTrue(CloudSaves.belongsToGame("Dr. Mario (USA).sav", CloudSaves.gameStem("Dr. Mario (USA).nes")))
        assertEquals("Game (Europe)", CloudSaves.gameStem("Game%20(Europe).zip"))
        assertEquals("Mario + Luigi (USA)", CloudSaves.gameStem("gba/Mario + Luigi (USA).gba"))
        assertFalse(CloudSaves.belongsToGame("x.srm", ""))
    }

    // ---- Versions and device files ------------------------------------------------------------

    private fun entry(id: Int, name: String, updated: String, kind: SaveKind = SaveKind.SAVE, size: Long = 100, slot: String? = null, romId: Int = 42) =
        CloudSaveEntry(kind, id, romId, name, "mGBA", updated, SaveSyncPlanner.epochMillis(updated), size, "/d/$id", null, slot)

    private fun local(path: String, size: Long = 100, modified: Long = 1_000L, kind: SaveKind = SaveKind.SAVE) = LocalSaveFile(kind, path, size, modified)

    private fun record(local: LocalSaveFile, remote: CloudSaveEntry) = SaveSyncEngine.record(local, remote.toRemote())

    @Test fun `versions are newest first with the current one and the one on the device marked`() {
        val old = entry(1, "Game.srm", "2026-10-01T10:00:00+00:00")
        val current = entry(2, "Game.srm", "2026-10-03T10:00:00+00:00")
        val slot = entry(3, "Game [slot].srm", "2026-10-04T10:00:00+00:00", slot = "1")
        val device = local("mGBA/Game.srm")
        val versions = CloudSaves.versions(listOf(old, current, slot), listOf(record(device, current)), listOf(device), "http://h")
        assertEquals(listOf(3, 2, 1), versions.map { it.entry.id })
        assertEquals(listOf(false, true, false), versions.map { it.current })
        assertEquals(listOf(false, true, false), versions.map { it.onDevice })
        // The device file changed since: no longer "on this device".
        val changed = CloudSaves.versions(listOf(current), listOf(record(device, current)), listOf(device.copy(size = 200)))
        assertFalse(changed.single().onDevice)
    }

    @Test fun `device files are tied to the game and compared with the server`() {
        val server = entry(2, "Game.srm", "2026-10-03T10:00:00+00:00")
        val synced = local("mGBA/Game.srm")
        val records = mapOf(SaveSyncPlanner.key(SaveKind.SAVE, synced.path) to record(synced, server))
        val inSync = CloudSaves.deviceSaves(listOf(synced, local("Other.srm")), "Game", 42, records, listOf(server))
        assertEquals(1, inSync.size)
        assertEquals(DeviceSaveState.IN_SYNC, inSync.single().state)
        assertFalse(inSync.single().canUpload)

        val played = CloudSaves.deviceSaves(listOf(synced.copy(modified = 5_000L)), "Game", 42, records, listOf(server)).single()
        assertEquals(DeviceSaveState.DEVICE_NEWER, played.state)
        assertTrue(played.canUpload)

        val serverMoved = server.copy(updatedAt = "2026-10-04T10:00:00+00:00", updatedMillis = SaveSyncPlanner.epochMillis("2026-10-04T10:00:00+00:00"))
        assertEquals(DeviceSaveState.SERVER_NEWER, CloudSaves.deviceSaves(listOf(synced), "Game", 42, records, listOf(serverMoved)).single().state)
        assertEquals(DeviceSaveState.BOTH_CHANGED, CloudSaves.deviceSaves(listOf(synced.copy(size = 7)), "Game", 42, records, listOf(serverMoved)).single().state)

        val fresh = CloudSaves.deviceSaves(listOf(local("Game.state1", kind = SaveKind.STATE)), "Game", 42, emptyMap(), listOf(server)).single()
        assertEquals(DeviceSaveState.NOT_ON_SERVER, fresh.state)
        assertTrue(fresh.canUpload)

        // Never synced: the clocks decide.
        val newerHere = local("Game.srm", modified = SaveSyncPlanner.epochMillis("2026-10-05T10:00:00+00:00")!!)
        assertEquals(DeviceSaveState.DEVICE_NEWER, CloudSaves.deviceSaves(listOf(newerHere), "Game", 42, emptyMap(), listOf(server)).single().state)
        val olderHere = local("Game.srm", modified = SaveSyncPlanner.epochMillis("2026-10-01T10:00:00+00:00")!!)
        assertEquals(DeviceSaveState.SERVER_NEWER, CloudSaves.deviceSaves(listOf(olderHere), "Game", 42, emptyMap(), listOf(server)).single().state)
    }

    @Test fun `a renamed file is still the game's when a sync tied it to the rom`() {
        val server = entry(2, "Game_clean.srm", "2026-10-03T10:00:00+00:00")
        val device = local("Weird Name.srm")
        val records = mapOf(SaveSyncPlanner.key(SaveKind.SAVE, device.path) to record(device, server))
        val saves = CloudSaves.deviceSaves(listOf(device), "Game", 42, records, listOf(server))
        assertEquals(DeviceSaveState.IN_SYNC, saves.single().state)
        assertTrue(CloudSaves.deviceSaves(listOf(device), "Game", 7, records, listOf(server)).isEmpty())
    }

    // ---- Restore target -----------------------------------------------------------------------

    private val folders = mapOf(SaveKind.SAVE to setOf("mGBA", "Snes9x"), SaveKind.STATE to setOf("mGBA"))

    @Test fun `restore goes to the file a sync paired with the version`() {
        val v = entry(2, "Game.srm", "2026-10-03T10:00:00+00:00")
        // Paired with the Snes9x copy although the server says mGBA: the pairing wins.
        val paired = local("Snes9x/Game.srm")
        val target = CloudSaves.restoreTarget(v, "Game", listOf(local("mGBA/Game.srm"), paired), listOf(record(paired, v)), folders)
        assertEquals(RestoreTarget.Path("Snes9x/Game.srm"), target)
    }

    @Test fun `restore picks the same name in the emulator's folder and refuses to guess`() {
        val v = entry(2, "Game.srm", "2026-10-03T10:00:00+00:00")
        assertEquals(RestoreTarget.Path("mGBA/Game.srm"), CloudSaves.restoreTarget(v, "Game", listOf(local("mGBA/Game.srm"), local("Snes9x/Game.srm")), emptyList(), folders))
        val other = v.copy(emulator = "RetroArch")
        assertEquals(RestoreTarget.Ambiguous, CloudSaves.restoreTarget(other, "Game", listOf(local("mGBA/Game.srm"), local("Snes9x/Game.srm")), emptyList(), folders))
        assertEquals(RestoreTarget.Path("Snes9x/Game.srm"), CloudSaves.restoreTarget(other, "Game", listOf(local("Snes9x/Game.srm")), emptyList(), folders))
    }

    @Test fun `a slot save goes over the game's own file of its type`() {
        val slot = entry(3, "Game [2026-10-04 20-00-00].srm", "2026-10-04T10:00:00+00:00", slot = "1")
        assertEquals(RestoreTarget.Path("mGBA/Game.srm"), CloudSaves.restoreTarget(slot, "Game", listOf(local("mGBA/Game.srm"), local("mGBA/Game.state", kind = SaveKind.STATE)), emptyList(), folders))
        // Nothing on the device yet: a new file with the plain name, in the emulator's folder.
        assertEquals(RestoreTarget.Path("mGBA/Game.srm"), CloudSaves.restoreTarget(slot, "Game", emptyList(), emptyList(), folders))
    }

    @Test fun `a new file goes where a sync would put it, or nowhere without a folder`() {
        val v = entry(2, "Game.state1", "2026-10-03T10:00:00+00:00", kind = SaveKind.STATE).copy(emulator = "gpsp")
        assertEquals(RestoreTarget.Path("Game.state1"), CloudSaves.restoreTarget(v, "Game", emptyList(), emptyList(), folders))
        assertEquals(RestoreTarget.NoFolder, CloudSaves.restoreTarget(v, "Game", emptyList(), emptyList(), mapOf(SaveKind.SAVE to emptySet())))
        val s = entry(4, "Game.srm", "2026-10-03T10:00:00+00:00").copy(emulator = "DraStic")
        assertEquals(RestoreTarget.NoFolder, CloudSaves.restoreTarget(s, "Game", emptyList(), emptyList(), folders, noRootFolder = setOf(SaveKind.SAVE)))
    }

    @Test fun `the record after a restore makes the next sync send the restored version up`() {
        val current = entry(2, "Game.srm", "2026-10-03T10:00:00+00:00")
        val old = entry(1, "Game.srm", "2026-10-01T10:00:00+00:00")
        val written = local("mGBA/Game.srm", size = 90, modified = 9_000L)

        val same = CloudSaves.recordAfterRestore(written, current, current, null)!!
        assertEquals(2, same.remoteId)
        assertEquals(90L, same.localSize)

        val older = CloudSaves.recordAfterRestore(written, old, current, null)!!
        assertEquals(2, older.remoteId)
        assertEquals(-1L, older.localSize)
        // What the planner does with it: the device side changed, the server did not → upload.
        val actions = SaveSyncPlanner.plan(listOf(written), listOf(current.toRemote()), listOf(older), folders)
        assertTrue(actions.single() is SaveSyncAction.Upload)

        val existing = SaveSyncRecord(SaveKind.SAVE, written.path, 42, 77, "x", 1, 1)
        assertEquals(existing, CloudSaves.recordAfterRestore(written, null, null, existing))
        assertNull(CloudSaves.recordAfterRestore(written, null, null, null))
    }

    @Test fun `a device copy holds the server version only while neither side changed`() {
        val server = entry(2, "Game.srm", "2026-10-03T10:00:00+00:00")
        val device = local("Game.srm")
        val r = record(device, server)
        assertTrue(CloudSaves.deviceHolds(server, r, device))
        assertFalse(CloudSaves.deviceHolds(server, r, device.copy(modified = 2)))
        assertFalse(CloudSaves.deviceHolds(server.copy(size = 5), r, device))
        assertFalse(CloudSaves.deviceHolds(server, null, device))
        assertTrue(CloudSaves.serverUnchangedSinceSync(server, r))
        assertFalse(CloudSaves.serverUnchangedSinceSync(server.copy(updatedAt = "2026-10-04T10:00:00+00:00"), r))
    }

    // ---- Safety copies ------------------------------------------------------------------------

    @Test fun `safety copies are read from their folder layout`() {
        val zone = ZoneOffset.UTC
        val copy = CloudSaves.safetyCopy("20261003-120000/saves/mGBA/Game.srm", 100, 5L, zone)!!
        assertEquals(SaveKind.SAVE, copy.kind)
        assertEquals("mGBA/Game.srm", copy.path)
        assertEquals("Game.srm", copy.name)
        assertEquals(1791028800000L, copy.takenAt)
        assertEquals(1791028800000L, CloudSaves.safetyCopy("20261003-120000-2/states/Game.state", 1, 5L, zone)!!.takenAt)
        assertEquals(5L, CloudSaves.safetyCopy("odd-stamp/states/Game.state", 1, 5L, zone)!!.takenAt)
        assertNull(CloudSaves.safetyCopy("20261003-120000/other/Game.srm", 1, 5L, zone))
        assertNull(CloudSaves.safetyCopy("20261003-120000/saves/Game.srm.tmp", 1, 5L, zone))
        assertNull(CloudSaves.safetyCopy("Game.srm", 1, 5L, zone))
        assertNotNull(CloudSaves.parseStamp("20261003-120000", ZoneId.of("Europe/Brussels")))
    }

    @Test fun `the game's safety copies are found by name or by the rom a sync tied them to`() {
        val zone = ZoneOffset.UTC
        val a = CloudSaves.safetyCopy("20261001-120000/saves/mGBA/Game.srm", 1, 0, zone)!!
        val b = CloudSaves.safetyCopy("20261003-120000/saves/mGBA/Game.srm", 1, 0, zone)!!
        val renamed = CloudSaves.safetyCopy("20261002-120000/saves/Weird.srm", 1, 0, zone)!!
        val other = CloudSaves.safetyCopy("20261002-120000/saves/Other.srm", 1, 0, zone)!!
        val records = mapOf(SaveSyncPlanner.key(SaveKind.SAVE, "Weird.srm") to SaveSyncRecord(SaveKind.SAVE, "Weird.srm", 42, 1, "", 1, 1))
        val mine = CloudSaves.safetyCopiesFor(listOf(a, b, renamed, other), "Game", 42, records)
        assertEquals(listOf(b, renamed, a), mine)
    }
}
