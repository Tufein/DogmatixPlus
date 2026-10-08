package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.local.entity.FavouriteEntity
import com.cortinadev.dogmatix.data.service.LibraryOperation
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class Release25Test {
    @Test fun rulesCombineAllFiltersAndTreatUnknownMetadataAsUnknown() {
        val rule = SmartCollectionRule(setOf("gba"), setOf("NL"), "Action", 2000, 2005, "NEVER")
        assertTrue(rule.matches("gba", listOf("nl"), "Puzzle|Action", 2001, false))
        assertFalse(rule.matches("nds", listOf("NL"), "Action", 2001, false))
        assertFalse(rule.matches("gba", listOf("EN"), "Action", 2001, false))
        assertFalse(rule.matches("gba", listOf("NL"), null, 2001, false))
        assertFalse(rule.matches("gba", listOf("NL"), "Action", null, false))
        assertFalse(rule.matches("gba", listOf("NL"), "Action", 2001, true))
        assertFalse(rule.matches("gba", listOf("NL"), "Action", 2006, false))
        assertTrue(SmartCollectionRule().matches("any", emptyList(), null, null, true))
    }
    @Test fun stableRuleFormatRoundTripsAndRejectsMalformedData() {
        val rules = mapOf(4L to SmartCollectionRule(setOf("gba"), setOf("NL", "EN"), "Action", 2001, 2004, "PLAYED"))
        assertEquals(rules, SmartCollectionRules.decode(SmartCollectionRules.encode(rules)))
        assertEquals(mapOf(1L to SmartCollectionRule()), SmartCollectionRules.decode("""{"1":{}}"""))
        listOf("[]", "{", """{"1":{"fromYear":1.5}}""", """{"1":{"languages":null}}""", """{"1":{"played":"maybe"}}""",
            """{"-1":{}}""", """{"1":{"fromYear":2005,"toYear":2000}}""").forEach { assertNull(it, SmartCollectionRules.decode(it)) }
    }
    @Test fun sharedGameMustFitEveryCollectionAndConsumesEachLimitOnce() {
        val game = OfflineCollections.Game("gba", "shared.gba")
        val other = OfflineCollections.Game("gba", "other.gba")
        val kept = listOf(OfflineCollections.Kept(1, "one", listOf(game, game, other)), OfflineCollections.Kept(2, "two", listOf(game)))
        val offers = mapOf(game to OfflineCollections.Offer(60, false), other to OfflineCollections.Offer(50, false))
        val plan = OfflineCollections.plan(kept, offers, { false }, { false }, null, 0, 10, mapOf(1L to 100, 2L to 100))
        assertEquals(listOf(game), plan.picks.map { it.game })
        assertEquals(listOf(1L, 2L), plan.picks.single().collectionIds)
        assertEquals(1, plan.tallies.first().quota)
        val blocked = OfflineCollections.plan(kept, offers, { false }, { false }, null, 0, 10, mapOf(2L to 50))
        assertEquals(listOf(other), blocked.picks.map { it.game })
    }
    @Test fun quotasCountExistingAndQueuedSpaceAndHoldUnknownSizes() {
        val game = OfflineCollections.Game("gba", "new.zip")
        val existing = OfflineCollections.Game("gba", "existing.gba")
        val kept = listOf(OfflineCollections.Kept(1, "one", listOf(existing, game)))
        val archive = mapOf(game to OfflineCollections.Offer(30, true))
        val full = OfflineCollections.plan(kept, archive, { it == existing }, { false }, null, 0, 10, mapOf(1L to 100), mapOf(1L to 50))
        assertTrue(full.picks.isEmpty())
        assertEquals(1, full.tallies.single().onDevice)
        assertEquals(1, full.tallies.single().quota)
        val unknown = OfflineCollections.plan(kept, mapOf(game to OfflineCollections.Offer(0, false)), { it == existing }, { false }, null, 0, 10, mapOf(1L to 100))
        assertEquals(1, unknown.tallies.single().quota)
        assertTrue(OfflineCollections.plan(kept, archive, { it == existing }, { it == game }, null, 0, 10, mapOf(1L to 10), mapOf(1L to 50)).picks.isEmpty())
    }
    @Test fun internalAndSdSpaceBudgetsAreIndependentAndReserveTheQueue() {
        val first = OfflineCollections.Game("gba", "internal.gba")
        val second = OfflineCollections.Game("nds", "sd.nds")
        val third = OfflineCollections.Game("nds", "sd2.nds")
        val plan = OfflineCollections.plan(listOf(OfflineCollections.Kept(1, "all", listOf(first, second, third))),
            mapOf(first to OfflineCollections.Offer(50, false, "internal"), second to OfflineCollections.Offer(60, false, "sd"), third to OfflineCollections.Offer(60, false, "sd")),
            { false }, { false }, null, 0, 10, freeByVolume = mapOf("internal" to OfflineCollections.SPACE_MARGIN + 40, "sd" to OfflineCollections.SPACE_MARGIN + 130),
            reserveByVolume = mapOf("internal" to 0, "sd" to 20))
        assertEquals(listOf(second), plan.picks.map { it.game })
        assertEquals(2, plan.noSpace)
    }
    @Test fun hugeSourceSizesCannotOverflowEstimates() {
        assertEquals(Long.MAX_VALUE, OfflineCollections.need(OfflineCollections.Offer(Long.MAX_VALUE, true)))
        assertEquals(Long.MAX_VALUE, SpaceMath.sum(listOf(Long.MAX_VALUE, 1)))
        assertEquals(0L, SpaceMath.need(-10, true))
        assertTrue(SpaceMath.available(50, Long.MAX_VALUE, 100) < 0)
    }
    @Test fun discReferencesMustBeSafeAndInTheSameFolder() {
        assertTrue(GameReadiness.referencesPresent("Game.cue", "FILE \"Track 01.bin\" BINARY", listOf("track 01.bin")))
        assertFalse(GameReadiness.referencesPresent("Game.cue", "FILE \"Track 01.bin\" BINARY", emptyList()))
        assertFalse(GameReadiness.referencesPresent("Game.cue", "FILE \"../save.srm\" BINARY", listOf("../save.srm")))
        assertFalse(GameReadiness.referencesPresent("Game.m3u", "save.srm", listOf("save.srm")))
        assertFalse(GameReadiness.referencesPresent("Game.m3u", "# comment", emptyList()))
        assertTrue(GameReadiness.referencesPresent("Game.m3u", "Disc1.chd\nDisc2.chd", listOf("Disc1.chd", "Disc2.chd")))
    }
    @Test fun profileBackupPreservesTwoFavouritesAndRejectsWrongPreferenceTypes() {
        val favourites = listOf(FavouriteEntity("gba", "Game.gba", 1, ""), FavouriteEntity("gba", "Game.gba", 2, "child"))
        assertEquals(favourites, BackupJson.favouritesFromJson(BackupJson.favouritesToJson(favourites)))
        assertNull(BackupJson.decodeSetting("personal:child:favorite_languages", JsonParser.parseString("""{"t":"s","v":"EN"}""")))
        assertNull(BackupJson.decodeSetting("personal:child:fixed_version:gba|game|", JsonParser.parseString("""{"t":"ss","v":["Game.gba"]}""")))
        assertEquals(setOf("EN"), BackupJson.decodeSetting("personal:child:favorite_languages", JsonParser.parseString("""{"t":"ss","v":["EN"]}""")))
    }
    @Test fun failureHelpUsesPublicCategoriesOnlyAndToleratesUnknownReasons() {
        fun entry(reason: String) = ActionEntry("id", 1, ActionKind.DOWNLOAD_FAILED, reason = ActionReason.DOWNLOAD_PREFIX + reason)
        assertEquals(ActionHelp.SPACE, ActionHelp.of(entry("STORAGE_FULL")))
        assertEquals(ActionHelp.VERIFY, ActionHelp.of(entry("VERIFICATION")))
        assertEquals(ActionHelp.GENERAL, ActionHelp.of(entry("future-code")))
        assertNull(ActionHelp.of(ActionEntry("id", 1, ActionKind.PLAYED)))
    }
    @Test fun oldReleaseRecoveryJournalRemainsReadableAndNewFieldsAreStable() {
        val old = """{"a":"old-id","b":"trash","c":"Game","d":123,"e":"stored","f":"","g":"","h":[{"a":"source","b":"backup","c":"Game.gba","d":7,"e":"sha","f":"parent","g":false}],"i":"gba","j":1,"k":""}"""
        val operation = Gson().fromJson(old, LibraryOperation::class.java)
        assertEquals("old-id", operation.id)
        assertEquals("trash", operation.kind)
        assertEquals("Game.gba", operation.files.single().name)
        assertNull(operation.files.single().relativePath)
        assertNull(operation.directories)
        val json = JsonParser.parseString(Gson().toJson(operation)).asJsonObject
        assertTrue(json.has("kind"))
        assertFalse(json.has("b"))
    }
}
