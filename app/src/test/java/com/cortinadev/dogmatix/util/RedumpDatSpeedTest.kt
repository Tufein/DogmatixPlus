package com.cortinadev.dogmatix.util

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipInputStream

/** A real Redump DAT (fetched by hand into /tmp/redump_psx.bin) parses fully and quickly; skipped without it. */
class RedumpDatSpeedTest {
    @Test fun `a full redump dat parses`() {
        val file = File("/tmp/redump_psx.bin")
        assumeTrue(file.exists())
        val text = ZipInputStream(file.inputStream()).use { z -> z.nextEntry; z.readBytes().toString(Charsets.UTF_8) }
        val started = System.currentTimeMillis()
        val dat = DatParser.parse(text)
        val took = System.currentTimeMillis() - started
        println("Redump PSX: ${dat.games.size} games, ${dat.romCount} files in $took ms")
        assertTrue(dat.games.size > 10_000)
        assertTrue(dat.games.all { g -> g.roms.all { it.crc != null && it.sha1 != null } })
    }
}
