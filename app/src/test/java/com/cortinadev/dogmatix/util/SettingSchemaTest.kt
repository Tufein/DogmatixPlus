package com.cortinadev.dogmatix.util

import java.io.File
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class SettingSchemaTest {
    @Test fun `every concrete preference reader has a backup type rule`() {
        val source = listOf(File("src/main/java/com/cortinadev/dogmatix"), File("app/src/main/java/com/cortinadev/dogmatix"))
            .first { it.isDirectory }
        val readers = Regex("(boolean|int|long|float|double|string|stringSet)PreferencesKey\\(\"([^\"$]+)\"\\)")
        val tags = mapOf("boolean" to "b", "int" to "i", "long" to "l", "float" to "f", "double" to "d", "string" to "s", "stringSet" to "ss")
        source.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            readers.findAll(file.readText()).forEach { match ->
                assertEquals("${file.name}: ${match.groupValues[2]}", tags[match.groupValues[1]], SettingSchema.expectedType(match.groupValues[2]))
            }
        }
    }

    @Test fun `all known settings reject a mismatched type including personal profiles`() {
        SettingSchema.types.forEach { (name, type) ->
            val bad = if (type == "s") """{"t":"b","v":true}""" else """{"t":"s","v":"false"}"""
            assertNull(name, BackupJson.decodeSetting(name, JsonParser.parseString(bad)))
            assertNull("personal $name", BackupJson.decodeSetting("personal:child:$name", JsonParser.parseString(bad)))
        }
        assertNull(BackupJson.decodeSetting("personal:child:fixed_version:gba|game", JsonParser.parseString("""{"t":"b","v":true}""")))
    }

    @Test fun `restored automation and storage values stay inside supported ranges`() {
        fun integer(name: String, value: Long) = BackupJson.decodeSetting(name, JsonParser.parseString("""{"t":"i","v":$value}"""))
        assertEquals(1, integer("auto_scan_hours", -20))
        assertEquals(10, integer("per_server_limit", 100))
        assertEquals(OfflineCollections.MAX_CAP, integer("offline_collections_cap", 999))
        assertEquals(100, integer("offline_collections_reserve_gb", 999))
        assertEquals(SmartStorage.MIN_RECENT_DAYS, integer("smart_storage_recent_days", 0))
        assertEquals(PowerRules.MIN_PERCENT, integer("personal:child:power_battery_percent", -1))
        assertNull(integer("auto_scan_hours", Long.MAX_VALUE))
        assertNull(BackupJson.decodeSetting("auto_scan_hours", JsonParser.parseString("""{"t":"i","v":1.5}""")))
    }

    @Test fun `invalid quota entries cannot become unsafe budgets`() {
        val value = JsonParser.parseString("""{"t":"ss","v":["1:5","2:-1","3:99999","broken",":7","0:5"]}""")
        assertEquals(setOf("1:5"), BackupJson.decodeSetting("offline_collections_quotas", value))
    }
}
