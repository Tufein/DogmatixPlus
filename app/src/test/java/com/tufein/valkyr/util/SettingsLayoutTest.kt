package com.tufein.valkyr.util

import com.tufein.valkyr.ui.navigation.NavRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        SettingsLayout.parents.keys.forEach { assertFalse(it, SettingsLayout.isShown(it, off)) }
        (allKeys - SettingsLayout.parents.keys).forEach { assertTrue(it, SettingsLayout.isShown(it, off)) }
        assertTrue(SettingsLayout.isShown(null, off))

        assertTrue(SettingsLayout.isShown(SettingKeys.SPEED_DAY_ONLY, SettingsMasters(speedLimitOn = true)))
        assertTrue(SettingsLayout.isShown(SettingKeys.BATTERY_LEVEL, SettingsMasters(lowBatteryOn = true)))
        assertTrue(SettingsLayout.isShown(SettingKeys.DEBRID_KEY, SettingsMasters(debridOn = true)))
        assertTrue(SettingsLayout.isShown(SettingKeys.AUTO_BACKUP_FOLDER, SettingsMasters(autoBackupOn = true)))
        listOf(SettingKeys.AUTOSCAN_EVERY, SettingKeys.AUTOSCAN_WIFI, SettingKeys.AUTOSCAN_CHARGING, SettingKeys.AUTOSCAN_NIGHT).forEach {
            assertTrue(it, SettingsLayout.isShown(it, SettingsMasters(autoScanOn = true)))
        }
    }

    @Test fun `the night hours show for every switch that uses them`() {
        fun nightShown(m: SettingsMasters) =
            SettingsLayout.isShown(SettingKeys.NIGHT_START, m) && SettingsLayout.isShown(SettingKeys.NIGHT_END, m)
        assertFalse(nightShown(SettingsMasters()))
        assertTrue(nightShown(SettingsMasters(nightOnly = true)))
        // "No limit at night" counts only while there is a limit.
        assertTrue(nightShown(SettingsMasters(speedLimitOn = true, speedLimitDayOnly = true)))
        assertFalse(nightShown(SettingsMasters(speedLimitDayOnly = true)))
        // "Scan only at night" counts only while scanning automatically.
        assertTrue(nightShown(SettingsMasters(autoScanOn = true, autoScanNightOnly = true)))
        assertFalse(nightShown(SettingsMasters(autoScanNightOnly = true)))
    }

    @Test fun `search jumps to a row that always shows`() {
        assertEquals(SettingKeys.NIGHT, SettingsLayout.jumpTarget(SettingKeys.NIGHT_END))
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
}
