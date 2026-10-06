package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickActionsTest {
    @Test fun `each key with the quick action maps to its action`() {
        assertEquals(QuickAction.SURPRISE, QuickActions.parse(QuickActions.ACTION_QUICK, "surprise"))
        assertEquals(QuickAction.DOWNLOADS, QuickActions.parse(QuickActions.ACTION_QUICK, "downloads"))
        assertEquals(QuickAction.SEARCH, QuickActions.parse(QuickActions.ACTION_QUICK, "search"))
    }

    @Test fun `the keys are the ones the shortcuts put in the extra`() {
        QuickAction.values().forEach { assertEquals(it, QuickActions.parse(QuickActions.ACTION_QUICK, it.key)) }
        assertEquals(setOf("surprise", "downloads", "search"), QuickAction.values().map { it.key }.toSet())
    }

    @Test fun `case and spaces around the key do not matter`() {
        assertEquals(QuickAction.SEARCH, QuickActions.parse(QuickActions.ACTION_QUICK, "  Search "))
        assertEquals(QuickAction.SURPRISE, QuickActions.parse(QuickActions.ACTION_QUICK, "SURPRISE"))
    }

    @Test fun `other intents are not quick actions`() {
        assertNull(QuickActions.parse(null, "search"))
        assertNull(QuickActions.parse("android.intent.action.VIEW", "search"))
        assertNull(QuickActions.parse("android.intent.action.MAIN", null))
    }

    @Test fun `a missing or unknown key is not an action (a rotation after the extra was removed)`() {
        assertNull(QuickActions.parse(QuickActions.ACTION_QUICK, null))
        assertNull(QuickActions.parse(QuickActions.ACTION_QUICK, ""))
        assertNull(QuickActions.parse(QuickActions.ACTION_QUICK, "reboot"))
    }
}
