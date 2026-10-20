package com.tufein.valkyr.util

import org.junit.Assert.*
import org.junit.Test

class GamePackageTransferTest {
    private fun manifest(id: String = "a".repeat(64)) = GamePackages.Manifest(sourceIdentity = id,
        consoleId = "psx", fileName = "Demo.zip", rootUri = "content://private.provider/tree/device-secret",
        subPath = "My PSX", createdAt = 12, parts = listOf(GamePackages.Part("Disc 1/Track.bin", 10, "b".repeat(64))))
    @Test fun roundTripPreservesExactLayoutAndHashesWithoutExportingDeviceGrants() {
        val original = manifest()
        val bytes = GamePackageTransfer.encode(listOf(original))
        assertFalse(bytes.toString(Charsets.UTF_8).contains("device-secret"))
        assertEquals(listOf(original.copy(rootUri = GamePackageTransfer.PORTABLE_ROOT)), GamePackageTransfer.decode(bytes))
    }
    @Test fun duplicateIdentitiesCannotCreateConflictingRecordsForOneTarget() {
        assertThrows(IllegalArgumentException::class.java) { GamePackageTransfer.encode(listOf(manifest(), manifest().copy(subPath = "Other"))) }
    }
    @Test fun corruptFormatUnknownFieldsDuplicateFieldsAndNumericTypesAreRejected() {
        val valid = GamePackageTransfer.encode(listOf(manifest())).toString(Charsets.UTF_8)
        listOf(valid.replace("\"version\":1", "\"version\":1.0"), valid.replace("\"version\":1", "\"version\":\"1\""),
            valid.replace("\"version\":1", "\"version\":1,\"version\":1"), valid.dropLast(1) + ",\"extra\":true}",
            valid + "{}", valid.replace("dogmatix-game-packages", "other")).forEach { value ->
            assertThrows(Exception::class.java) { GamePackageTransfer.decode(value.toByteArray()) }
        }
    }
    @Test fun anImportedRootCannotSupplyItsOwnFolderGrant() {
        val bytes = GamePackageTransfer.encode(listOf(manifest())).toString(Charsets.UTF_8)
            .replace(GamePackageTransfer.PORTABLE_ROOT, "content://other/tree/unselected")
        assertThrows(IllegalArgumentException::class.java) { GamePackageTransfer.decode(bytes.toByteArray()) }
    }
    @Test fun emptyOversizedAndMalformedUtf8DocumentsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { GamePackageTransfer.encode(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { GamePackageTransfer.decode(ByteArray(GamePackageTransfer.MAX_BYTES + 1)) }
        assertThrows(Exception::class.java) { GamePackageTransfer.decode(byteArrayOf(0xc0.toByte(), 0xaf.toByte())) }
    }
}
