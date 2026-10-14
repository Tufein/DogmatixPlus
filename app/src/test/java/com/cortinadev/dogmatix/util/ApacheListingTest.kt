package com.cortinadev.dogmatix.util

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A plain Apache index, the way ScummVM's download server lists a game's folder. */
class ApacheListingTest {
    private val html = """
        <table>
         <tr><th valign="top"><img src="/icons/blank.gif" alt="[ICO]"></th><th><a href="?C=N;O=D">Name</a></th><th><a href="?C=M;O=A">Last modified</a></th><th><a href="?C=S;O=A">Size</a></th><th><a href="?C=D;O=A">Description</a></th></tr>
         <tr><th colspan="5"><hr></th></tr>
         <tr><td valign="top"><img src="/icons/back.gif" alt="[PARENTDIR]"></td><td><a href="/frs/extras/">Parent Directory</a></td><td>&nbsp;</td><td align="right">  - </td><td>&nbsp;</td></tr>
         <tr><td valign="top"><img src="/icons/compressed.gif" alt="[   ]"></td><td><a href="bass-cd-1.2.zip">bass-cd-1.2.zip</a></td><td align="right">2020-08-25 10:23  </td><td align="right"> 66M</td><td>&nbsp;</td></tr>
         <tr><td valign="top"><img src="/icons/unknown.gif" alt="[   ]"></td><td><a href="bass-cd-1.2.zip.sha256">bass-cd-1.2.zip.sha256</a></td><td align="right">2020-08-25 10:23  </td><td align="right"> 64 </td><td>&nbsp;</td></tr>
         <tr><td valign="top"><img src="/icons/compressed.gif" alt="[   ]"></td><td><a href="BASS-Floppy-1.3.zip">BASS-Floppy-1.3.zip</a></td><td align="right">2020-08-25 10:23  </td><td align="right">1.5K</td><td>&nbsp;</td></tr>
         <tr><td valign="top"><img src="/icons/unknown.gif" alt="[   ]"></td><td><a href="lonely.zip.sha256">lonely.zip.sha256</a></td><td align="right">2020-08-25 10:23  </td><td align="right"> 64 </td><td>&nbsp;</td></tr>
        </table>"""

    private val rows = Jsoup.parse(html).select("tr")
    private val listed = rows.mapNotNull { FileParsingUtils.linkOf(it)?.attr("href") }.toSet()

    private fun games() = rows.mapNotNull { row ->
        val link = FileParsingUtils.linkOf(row) ?: return@mapNotNull null
        val href = link.attr("href")
        if (href.endsWith("/") || FileParsingUtils.isChecksumSidecar(href, listed)) return@mapNotNull null
        FileParsingUtils.parseFileFromRow(row, "https://downloads.scummvm.org/frs/extras/Beneath%20a%20Steel%20Sky/", "pc_scummvm", link).first
    }

    @Test fun sizesGivenAsANumberAndOneUnitLetterAreRead() {
        val sizes = games().associate { it.name to it.fileSize }
        assertEquals(66L * 1024 * 1024, sizes["bass-cd-1.2.zip"])
        assertEquals(1536L, sizes["BASS-Floppy-1.3.zip"])
        assertEquals(66L * 1024 * 1024, FileSizeUtils.parseFileSize("66M"))
        assertEquals(1024L, FileSizeUtils.parseFileSize("1K"))
        assertEquals(40_000L, FileSizeUtils.parseFileSize("40 KB"))
        assertEquals(0L, FileSizeUtils.parseFileSize("64"))
        assertEquals(0L, FileSizeUtils.parseFileSize("2020-08-25"))
    }

    @Test fun aChecksumFileBesideItsDownloadIsNotListedAsAGame() {
        val names = games().map { it.fileName }
        assertEquals(listOf("bass-cd-1.2.zip", "BASS-Floppy-1.3.zip", "lonely.zip.sha256"), names)
        assertTrue(FileParsingUtils.isChecksumSidecar("bass-cd-1.2.zip.sha256", listed))
        assertTrue(FileParsingUtils.isChecksumSidecar("BASS-Floppy-1.3.zip.SHA256", listed + "BASS-Floppy-1.3.zip"))
        // Without the file it belongs to, it stays: it may be what the folder is about.
        assertFalse(FileParsingUtils.isChecksumSidecar("lonely.zip.sha256", listed))
        assertFalse(FileParsingUtils.isChecksumSidecar("bass-cd-1.2.zip", listed))
    }
}
