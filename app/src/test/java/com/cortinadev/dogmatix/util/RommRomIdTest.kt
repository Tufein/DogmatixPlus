package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RommRomIdTest {
    @Test fun `rom ids come out of romm download urls`() {
        assertEquals(42, RommSource.romIdOf(RommSource.downloadUrl("https://romm.local", 42, "a b.zip")))
        assertNull(RommSource.romIdOf("https://x.org/a.zip"))
    }
}
