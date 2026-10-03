package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionUtilsTest {

    @Test
    fun `numbers decide first`() {
        assertEquals(1, VersionUtils.compareVersions("v1.1.0", "1.0.0"))
        assertEquals(-1, VersionUtils.compareVersions("1.0.0", "1.0.1"))
        assertEquals(0, VersionUtils.compareVersions("v1.0.0", "1.0.0-debug"))
    }

    @Test
    fun `a pre-release comes before its release and after the one before`() {
        assertEquals(1, VersionUtils.compareVersions("v1.1.0", "1.1.0-beta.1"))
        assertEquals(1, VersionUtils.compareVersions("v1.1.0", "1.1.0-beta.1-debug"))
        assertEquals(1, VersionUtils.compareVersions("1.1.0-beta.1", "1.0.0"))
        assertEquals(-1, VersionUtils.compareVersions("1.1.0-beta.1", "1.1.0"))
    }
}
