package com.cortinadev.dogmatix.util

import org.junit.Assert.*
import org.junit.Test

class ArchivePlanTest {
    private fun entries(vararg names: String) = names.mapIndexed { index, name -> ArchivePlan.Entry(index, name, size = 3L) }
    private fun rejected(reason: ArchiveSafetyException.Reason, entries: List<ArchivePlan.Entry>) {
        assertEquals(reason, assertThrows(ArchiveSafetyException::class.java) { ArchivePlan.check(entries) }.reason)
    }

    @Test fun separateDiscsKeepTheirPathsAndTheirCombinedSize() {
        val checked = ArchivePlan.check(entries("Disc 1/Track.bin", "Disc 2/Track.bin"))
        assertEquals(listOf("Disc 1/Track.bin", "Disc 2/Track.bin"), checked.files.map { it.path })
        assertEquals(6L, checked.knownBytes)
    }
    @Test fun folderMarkersDoNotCountAsExtractedFiles() {
        val checked = ArchivePlan.check(listOf(ArchivePlan.Entry(0, "Disc/", directory = true), ArchivePlan.Entry(1, "Disc/Game.bin", size = 2L)))
        assertEquals(listOf(ArchivePlan.FileEntry(1, "Disc/Game.bin", 2L)), checked.files)
    }
    @Test fun backslashDirectoriesKeepTheirStructure() {
        assertEquals("Disc/Game.bin", ArchivePlan.check(entries("Disc\\Game.bin")).files.single().path)
    }
    @Test fun absoluteTraversalEmptyAndControlPathsAreRejected() {
        for (name in listOf("../Game.bin", "Disc/../Game.bin", "./Game.bin", "/Game.bin", "\\\\host\\Game.bin", "C:\\Game.bin", "a//b.bin", "a/", "bad\u0000.bin")) {
            rejected(ArchiveSafetyException.Reason.UNSAFE_PATH, entries(name))
        }
    }
    @Test fun duplicatesAndSanitizedFileAliasesAreRejected() {
        rejected(ArchiveSafetyException.Reason.NAME_COLLISION, entries("Game.bin", "Game.bin"))
        rejected(ArchiveSafetyException.Reason.NAME_COLLISION, entries("Game?.bin", "Game*.bin"))
        rejected(ArchiveSafetyException.Reason.NAME_COLLISION, entries("Game.bin", "Game.bin."))
    }
    @Test fun sanitizedDirectoryAliasesCannotMergeTwoFolders() {
        rejected(ArchiveSafetyException.Reason.NAME_COLLISION, entries("Disc?/one.bin", "Disc*/two.bin"))
    }
    @Test fun caseAndUnicodeAliasesCannotMergeOnPortableStorage() {
        rejected(ArchiveSafetyException.Reason.NAME_COLLISION, entries("Disc/one.bin", "disc/two.bin"))
        rejected(ArchiveSafetyException.Reason.NAME_COLLISION, entries("caf\u00e9.bin", "cafe\u0301.bin"))
    }
    @Test fun filesCannotBecomeParentDirectoriesInEitherOrder() {
        rejected(ArchiveSafetyException.Reason.NAME_COLLISION, entries("Disc", "Disc/game.bin"))
        rejected(ArchiveSafetyException.Reason.NAME_COLLISION, entries("Disc/game.bin", "Disc"))
    }
    @Test fun entryLimitIncludesDirectoriesAndIsCheckedBeforePaths() {
        assertEquals(ArchiveSafetyException.Reason.TOO_MANY_ENTRIES,
            assertThrows(ArchiveSafetyException::class.java) { ArchivePlan.check(entries("../bad", "ok"), maximumEntries = 1) }.reason)
    }
    @Test fun unknownSizeIsSupportedButCombinedSizeCannotOverflow() {
        assertEquals(0L, ArchivePlan.check(listOf(ArchivePlan.Entry(0, "Game.bin"))).knownBytes)
        rejected(ArchiveSafetyException.Reason.INSUFFICIENT_SPACE,
            listOf(ArchivePlan.Entry(0, "One.bin", size = Long.MAX_VALUE), ArchivePlan.Entry(1, "Two.bin", size = 1L)))
    }
    @Test fun deeplyNestedOrEmptySanitizedNamesAreRejected() {
        rejected(ArchiveSafetyException.Reason.UNSAFE_PATH, entries(List(65) { "dir" }.joinToString("/") + "/file.bin"))
        rejected(ArchiveSafetyException.Reason.UNSAFE_PATH, entries(" ... /file.bin"))
    }
}
