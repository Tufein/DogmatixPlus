package com.cortinadev.dogmatix.util

import org.junit.Assert.*
import org.junit.Test

class RecoveryScopeTest {
    @Test fun receiptOwnershipDoesNotLeakBetweenProfiles() {
        assertFalse(RecoveryScope.allows("child", LibraryRestrictions.NONE, "parent", "gba", emptyList()))
        assertFalse(RecoveryScope.allows("child", LibraryRestrictions.NONE, null, "gba", emptyList()))
        assertTrue(RecoveryScope.allows("child", LibraryRestrictions.NONE, "child", "gba", emptyList()))
        assertTrue(RecoveryScope.allows("", LibraryRestrictions.NONE, null, null, null))
    }

    @Test fun restrictedProfilesCannotRecoverUnknownOrHiddenGames() {
        val restrictions = LibraryRestrictions(hiddenConsoles = setOf("psx"), hiddenTags = setOf("Adult"))
        assertFalse(RecoveryScope.allows("child", restrictions, "child", null, emptyList()))
        assertFalse(RecoveryScope.allows("child", restrictions, "child", "psx", emptyList()))
        assertFalse(RecoveryScope.allows("child", restrictions, "child", "gba", null))
        assertFalse(RecoveryScope.allows("child", restrictions, "child", "gba", listOf("adult")))
        assertTrue(RecoveryScope.allows("child", restrictions, "child", "gba", listOf("Europe")))
    }
}
