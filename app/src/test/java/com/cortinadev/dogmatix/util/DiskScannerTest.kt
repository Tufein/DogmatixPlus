package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiskScannerTest {

    private val external = "com.android.externalstorage.documents"
    private val downloads = "com.android.providers.downloads.documents"

    @Test
    fun theSameFolderThroughTwoProvidersHasOnePath() {
        assertEquals(
            DiskScanner.canonicalPath(external, "primary:Download/ROMs/gba"),
            DiskScanner.canonicalPath(downloads, "raw:/storage/emulated/0/Download/ROMs/gba")
        )
        assertEquals("/storage/emulated/0/Download", DiskScanner.canonicalPath(downloads, "downloads"))
    }

    @Test
    fun volumesStayApart() {
        assertEquals("/storage/emulated/0/ROMs/psx", DiskScanner.canonicalPath(external, "primary:ROMs/psx"))
        assertEquals("/storage/1234-5678/ROMs/psx", DiskScanner.canonicalPath(external, "1234-5678:ROMs/psx"))
        assertEquals("/storage/emulated/0", DiskScanner.canonicalPath(external, "primary:"))
    }

    @Test
    fun unknownIdsHaveNoPath() {
        assertNull(DiskScanner.canonicalPath(downloads, "msf:123"))
        assertNull(DiskScanner.canonicalPath("com.example.cloud", "primary:ROMs"))
        assertNull(DiskScanner.canonicalPath(external, "no-colon"))
    }

    @Test
    fun aWorkProfilePathMatchesItsPrimaryId() {
        assertEquals(
            DiskScanner.canonicalPath(external, "primary:Download/ROMs/gba"),
            DiskScanner.canonicalPath(downloads, "raw:/storage/emulated/10/Download/ROMs/gba")
        )
    }
}
