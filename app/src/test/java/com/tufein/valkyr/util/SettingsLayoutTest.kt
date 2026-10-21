package com.tufein.valkyr.util

import com.tufein.valkyr.R
import com.tufein.valkyr.ui.navigation.NavRoutes
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLayoutTest {

    private val allKeys = SettingsLayout.groups.values.flatten()

    @Test fun `every row sits in exactly one card and every card has rows`() {
        assertEquals(allKeys.size, allKeys.toSet().size)
        SettingsGroup.entries.forEach { assertTrue(it.name, SettingsLayout.groups[it].orEmpty().isNotEmpty()) }
        assertEquals(SettingsGroup.entries.toList(), SettingsLayout.groups.keys.toList())
    }

    @Test fun `only the tools card has no heading`() {
        assertEquals(listOf(SettingsGroup.TOOLS), SettingsGroup.entries.filter { it.title == null })
    }

    @Test fun `places follow the cards, unknown rows go last`() {
        val places = allKeys.map { SettingsLayout.placeOf(it) }
        assertEquals(places.sorted(), places)
        assertEquals(Int.MAX_VALUE, SettingsLayout.placeOf("not_a_row"))
        assertEquals(Int.MAX_VALUE, SettingsLayout.placeOf(null))
        assertNull(SettingsLayout.groupOf("not_a_row"))
    }

    @Test fun `a dependent row follows its master directly, in the same card`() {
        SettingsLayout.parents.forEach { (child, master) ->
            val group = SettingsLayout.groupOf(child)
            assertEquals(child, group, SettingsLayout.groupOf(master))
            val rows = SettingsLayout.groups.getValue(group!!)
            val between = rows.subList(rows.indexOf(master) + 1, rows.indexOf(child))
            // Only siblings (rows of the same master) may sit in between.
            assertTrue(child, rows.indexOf(master) < rows.indexOf(child))
            assertTrue(child, between.all { SettingsLayout.parents[it] == master })
        }
    }

    @Test fun `masters are never dependent rows themselves`() {
        SettingsLayout.parents.values.forEach { assertFalse(it, it in SettingsLayout.parents) }
    }

    @Test fun `dependent rows hide while their master is off, others always show`() {
        val off = SettingsMasters()
        val hidden = SettingsLayout.parents.keys - SettingsLayout.alwaysShownDependents
        hidden.forEach { assertFalse(it, SettingsLayout.isShown(it, off)) }
        (allKeys - hidden).forEach { assertTrue(it, SettingsLayout.isShown(it, off)) }
        assertTrue(SettingsLayout.isShown(null, off))

        assertTrue(SettingsLayout.isShown(SettingKeys.BATTERY_LEVEL, SettingsMasters(lowBatteryOn = true)))
        assertTrue(SettingsLayout.isShown(SettingKeys.DEBRID_KEY, SettingsMasters(debridOn = true)))
        assertTrue(SettingsLayout.isShown(SettingKeys.AUTO_BACKUP_FOLDER, SettingsMasters(autoBackupOn = true)))
        listOf(SettingKeys.AUTOSCAN_EVERY, SettingKeys.AUTOSCAN_WIFI, SettingKeys.AUTOSCAN_CHARGING, SettingKeys.AUTOSCAN_NIGHT).forEach {
            assertTrue(it, SettingsLayout.isShown(it, SettingsMasters(autoScanOn = true)))
        }
    }

    @Test fun `a dependent whose master does not explain it stays visible with a reason`() {
        assertEquals(setOf(SettingKeys.SPEED_DAY_ONLY, SettingKeys.NIGHT_START, SettingKeys.NIGHT_END), SettingsLayout.alwaysShownDependents)
        assertTrue(SettingsLayout.parents.keys.containsAll(SettingsLayout.alwaysShownDependents))
        val off = SettingsMasters()
        SettingsLayout.alwaysShownDependents.forEach {
            assertTrue(it, SettingsLayout.isShown(it, off))
            assertNotNull(it, SettingsLayout.inactiveReason(it, off))
        }
        assertEquals(R.string.settings44_limit_needed, SettingsLayout.inactiveReason(SettingKeys.SPEED_DAY_ONLY, off))
        assertNull(SettingsLayout.inactiveReason(SettingKeys.SPEED_DAY_ONLY, SettingsMasters(speedLimitOn = true)))
        assertEquals(R.string.settings44_night_unused, SettingsLayout.inactiveReason(SettingKeys.NIGHT_START, off))
        assertNull(SettingsLayout.inactiveReason(SettingKeys.NIGHT_END, SettingsMasters(nightOnly = true)))
        // Rows that hide while their master is off never need a reason, nor do independent rows.
        (allKeys - SettingsLayout.alwaysShownDependents).forEach { assertNull(it, SettingsLayout.inactiveReason(it, off)) }
        assertNull(SettingsLayout.inactiveReason(null, off))
    }

    @Test fun `the night hours show for every switch that uses them`() {
        fun inUse(m: SettingsMasters) = SettingsLayout.inactiveReason(SettingKeys.NIGHT_START, m) == null &&
            SettingsLayout.inactiveReason(SettingKeys.NIGHT_END, m) == null
        assertFalse(inUse(SettingsMasters()))
        assertTrue(inUse(SettingsMasters(nightOnly = true)))
        // "No limit at night" counts only while there is a limit.
        assertTrue(inUse(SettingsMasters(speedLimitOn = true, speedLimitDayOnly = true)))
        assertFalse(inUse(SettingsMasters(speedLimitDayOnly = true)))
        // "Scan only at night" counts only while scanning automatically.
        assertTrue(inUse(SettingsMasters(autoScanOn = true, autoScanNightOnly = true)))
        assertFalse(inUse(SettingsMasters(autoScanNightOnly = true)))
        // The rows are visible in every case: the hours can always be seen and changed.
        listOf(SettingsMasters(), SettingsMasters(nightOnly = true)).forEach {
            assertTrue(SettingsLayout.isShown(SettingKeys.NIGHT_START, it))
            assertTrue(SettingsLayout.isShown(SettingKeys.NIGHT_END, it))
        }
    }

    @Test fun `search jumps to a row that always shows`() {
        // Hidden dependents jump to their master; dependents that always show are the target themselves.
        assertEquals(SettingKeys.AUTOSCAN, SettingsLayout.jumpTarget(SettingKeys.AUTOSCAN_NIGHT))
        assertEquals(SettingKeys.NIGHT_END, SettingsLayout.jumpTarget(SettingKeys.NIGHT_END))
        assertEquals(SettingKeys.THEME, SettingsLayout.jumpTarget(SettingKeys.THEME))
        allKeys.forEach { assertTrue(it, SettingsLayout.isShown(SettingsLayout.jumpTarget(it), SettingsMasters())) }
    }

    @Test fun `search names the card a row is in and jumps to a known row`() {
        val settings = SearchIndex.entries.filter { it.group == SearchGroup.SETTINGS }
        settings.forEach { entry ->
            val key = entry.target.rowKey!!
            val group = SettingsLayout.groupOf(key)
            assertTrue(entry.id, group != null)
            assertEquals(entry.id, group!!.title, entry.section)
            assertEquals(entry.id, NavRoutes.Settings.route, entry.target.route)
            assertTrue(entry.id, SettingsLayout.isShown(key, SettingsMasters()))
        }
    }

    @Test fun `rows that open their own screen are found as that screen`() {
        val routes = SearchIndex.entries.map { it.target.route }.toSet()
        listOf(NavRoutes.Tools, NavRoutes.Storage, NavRoutes.Profiles, NavRoutes.Cloud, NavRoutes.Romm, NavRoutes.SaveSync, NavRoutes.CloudBackup, NavRoutes.ReportProblem, NavRoutes.Contact)
            .forEach { assertTrue(it.route, it.route in routes) }
    }

    private fun declaredKeys(): List<String> = SettingKeys::class.java.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .map { it.isAccessible = true; it.get(null) as String }

    @Test fun `every setting key has exactly one home`() {
        val keys = declaredKeys()
        assertTrue(keys.size > 80)
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(emptyList<String>(), keys.filter { SettingsLayout.groupOf(it) == null })
        // And the layout holds nothing that is not a key.
        assertEquals(emptyList<String>(), allKeys.filter { it !in keys })
        assertEquals(keys.toSet(), allKeys.toSet())
    }

    @Test fun `cards stay within the bounds of a handheld screen`() {
        val headed = SettingsGroup.entries.filter { it.title != null }
        assertTrue(headed.size.toString(), headed.size in 9..13)
        SettingsLayout.groups.forEach { (group, rows) ->
            assertTrue("${group.name} has ${rows.size} rows", rows.size <= 10)
        }
    }

    @Test fun `every card says what it is for, the tools entry needs no words`() {
        SettingsGroup.entries.forEach {
            if (it == SettingsGroup.TOOLS) assertNull(it.description) else assertNotNull(it.name, it.description)
        }
        val titles = SettingsGroup.entries.mapNotNull { it.title }
        assertEquals(titles.size, titles.toSet().size)
        val descriptions = SettingsGroup.entries.mapNotNull { it.description }
        assertEquals(descriptions.size, descriptions.toSet().size)
    }

    @Test fun `search finds every setting that is not a link to its own screen`() {
        val found = SearchIndex.entries.filter { it.group == SearchGroup.SETTINGS }.map { it.id }.toSet()
        // These rows only open a tool, hub or screen: search lists that screen under its own name.
        val links = setOf(
            SettingKeys.TOOLS, SettingKeys.SEARCH_ALL, SettingKeys.STORAGE, SettingKeys.PROFILES, SettingKeys.CLOUD,
            SettingKeys.ROMM, SettingKeys.SAVE_SYNC, SettingKeys.CLOUD_BACKUP, SettingKeys.REPORT, SettingKeys.ABOUT,
            SettingKeys.FRONTEND_COVERS_TOOL, SettingKeys.META_TOOL, SettingKeys.FRONTEND_CHECK,
            SettingKeys.RETROACHIEVEMENTS, SettingKeys.NEW_HANDHELD, SettingKeys.RECOVERY
        )
        // The API key shows up through the Debrid service row it hangs under.
        val missing = allKeys.filter { it !in found && it !in links && it != SettingKeys.DEBRID_KEY }
        assertEquals(emptyList<String>(), missing)
        assertTrue(SettingKeys.DEBRID in found)
    }

    @Test fun `topics live in one card`() {
        fun card(key: String) = SettingsLayout.groupOf(key)
        // Night hours: the three switches that use them name their own card, the hours sit with the first.
        assertEquals(card(SettingKeys.NIGHT), card(SettingKeys.NIGHT_START))
        // Covers: the list switch and the sources screen are together.
        assertEquals(card(SettingKeys.LIST_COVERS), card(SettingKeys.COVERS_RETRY))
        // Folder topics: folder, per-console subfolders, free space and the storage link.
        listOf(SettingKeys.SEPARATE_CONSOLE, SettingKeys.MIN_FREE, SettingKeys.STORAGE).forEach {
            assertEquals(it, card(SettingKeys.DOWNLOAD_DIR), card(it))
        }
        // Backups: manual, automatic, restore and cloud copy.
        listOf(SettingKeys.AUTO_BACKUP, SettingKeys.BACKUP_IMPORT, SettingKeys.CLOUD_BACKUP).forEach {
            assertEquals(it, card(SettingKeys.BACKUP_EXPORT), card(it))
        }
        // The action comes before the option that modifies it only where the action applies a bundle.
        val app = SettingsLayout.groups.getValue(SettingsGroup.APP)
        assertTrue(app.indexOf(SettingKeys.UPDATE_CHECK) < app.indexOf(SettingKeys.PRERELEASES))
        // Presets set the speed limit and the conditions together: first in the card of the speed limit.
        assertEquals(card(SettingKeys.SPEED_LIMIT), card(SettingKeys.DOWNLOAD_PRESETS))
        assertEquals(SettingKeys.DOWNLOAD_PRESETS, SettingsLayout.groups.getValue(SettingsGroup.DOWNLOADS).first())
    }
}
