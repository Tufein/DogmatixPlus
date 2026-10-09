package com.cortinadev.dogmatix.util

import org.junit.Assert.*
import org.junit.Test

class DownloadPresetsTest {
    @Test fun presetsRoundTripThroughTheTypedBackupFormatWithoutAdditionalFields() {
        val preset = DownloadPreset("custom", "Night", DownloadPresetOptions(nightOnly = true, chargingOnly = true))
        val input = DownloadPresets.encode(listOf(preset))
        val backup = BackupJson.encodeSetting(input)
        val restored = BackupJson.decodeSetting(DownloadPresets.KEY, com.google.gson.JsonParser.parseString(backup.toString()))
        assertEquals(input, restored)
        assertEquals(listOf(preset), DownloadPresets.decode(restored as String))
    }

    @Test fun backupDropsMalformedPayloadAndWrongTypeButPreservesAnExplicitEmptyList() {
        assertNull(BackupJson.decodeSetting(DownloadPresets.KEY, BackupJson.encodeSetting(42)))
        assertNull(BackupJson.decodeSetting(DownloadPresets.KEY, BackupJson.encodeSetting("bad json")))
        assertNull(BackupJson.decodeSetting(DownloadPresets.KEY, BackupJson.encodeSetting("[null,{}]")))
        assertEquals("[]", BackupJson.decodeSetting(DownloadPresets.KEY, BackupJson.encodeSetting("[]")))
    }

    @Test fun presetBackupSanitizesLimitsAndNeverCarriesUnknownAccountOrFolderFields() {
        val input = """[{"id":"custom","name":"Test","concurrent":999,"perServer":99,"nightStart":-1,"speedKb":99999,"account":"not-preserved","folder":"not-preserved"}]"""
        val restored = BackupJson.decodeSetting(DownloadPresets.KEY, BackupJson.encodeSetting(input)) as String
        val options = DownloadPresets.decode(restored).single().options
        assertEquals(10, options.concurrentDownloads)
        assertEquals(10, options.perServerLimit)
        assertEquals(0, options.nightStart)
        assertEquals(5000f, options.limitSpeed)
        assertFalse(restored.contains("account")); assertFalse(restored.contains("folder"))
    }

    @Test fun unlimitedAndAllScheduleOptionsRoundTripAsValidJson() {
        val preset = DownloadPreset("custom", "Quiet evening", DownloadPresetOptions(
            concurrentDownloads = 2, perServerLimit = 1, wifiOnly = true, chargingOnly = true,
            nightOnly = true, nightStart = 1320, nightEnd = 390, speedLimitDayOnly = true))
        val encoded = DownloadPresets.encode(listOf(preset))
        assertFalse(encoded.contains("Infinity"))
        assertEquals(listOf(preset), DownloadPresets.decode(encoded))
        assertEquals(Float.POSITIVE_INFINITY, DownloadPresets.decode(encoded).single().options.limitSpeed)
    }

    @Test fun finiteSpeedDoesNotChangeUnitsOnRoundTrip() {
        val preset = DownloadPreset("custom", "Slow", DownloadPresetOptions(limitSpeed = 1250f))
        assertEquals(1250f, DownloadPresets.decode(DownloadPresets.encode(listOf(preset))).single().options.limitSpeed)
    }

    @Test fun malformedRowsDoNotHideOtherPresetsAndOversizedInputIsIgnored() {
        val good = DownloadPresets.encode(listOf(DownloadPreset("valid", "Valid", DownloadPresetOptions())))
        val mixed = good.dropLast(1) + ",null,{\"id\":\"\",\"name\":\"missing id\"},{\"id\":\"bad\",\"name\":\"Bad\",\"nightStart\":{}}]"
        assertEquals("valid", DownloadPresets.decode(mixed).single().id)
        assertTrue(DownloadPresets.decode("not json").isEmpty())
        assertTrue(DownloadPresets.decode(" ".repeat(131073)).isEmpty())
    }

    @Test fun corruptedValuesStayWithinExistingDownloadBounds() {
        val bounded = DownloadPresetOptions(Float.NaN, 100, -1, nightStart = -50, nightEnd = 9999).bounded()
        assertEquals(Float.POSITIVE_INFINITY, bounded.limitSpeed)
        assertEquals(10, bounded.concurrentDownloads)
        assertEquals(0, bounded.perServerLimit)
        assertEquals(0, bounded.nightStart)
        assertEquals(1439, bounded.nightEnd)
        assertEquals(5000f, DownloadPresetOptions(limitSpeed = Float.MAX_VALUE).bounded().limitSpeed)
        assertEquals(1, DownloadPresetOptions(concurrentDownloads = 0).bounded().concurrentDownloads)
        assertEquals(Float.POSITIVE_INFINITY, DownloadPresetOptions(limitSpeed = -1f).bounded().limitSpeed)
    }

    @Test fun builtInOverridesKeepStableIdsAndCustomRowsAreBounded() {
        val changed = DownloadPresets.builtIns.first().copy(options = DownloadPresetOptions(limitSpeed = 500f))
        val custom = (0..99).map { DownloadPreset("custom-$it", "Preset $it", DownloadPresetOptions()) }
        val all = DownloadPresets.all(listOf(changed, changed) + custom)
        assertEquals(32, all.size)
        assertEquals(500f, all.first().options.limitSpeed)
        assertEquals(DownloadPresets.NIGHT_ID, all[1].id)
        assertEquals(32, all.map { it.id }.distinct().size)
        assertEquals(30, DownloadPresets.decode(DownloadPresets.encode(custom)).size)
    }

    @Test fun nightAndDayDefaultsHaveDistinctTransferBehaviour() {
        val day = DownloadPresets.builtIns[0].options
        val night = DownloadPresets.builtIns[1].options
        assertTrue(day.limitSpeed.isFinite())
        assertEquals(1, day.concurrentDownloads)
        assertFalse(day.nightOnly)
        assertEquals(Float.POSITIVE_INFINITY, night.limitSpeed)
        assertTrue(night.concurrentDownloads > day.concurrentDownloads)
        assertTrue(night.wifiOnly && night.chargingOnly && night.nightOnly)
        assertTrue(DownloadPolicy.inWindow(60, night.nightStart, night.nightEnd))
        assertFalse(DownloadPolicy.inWindow(600, night.nightStart, night.nightEnd))
    }
}
