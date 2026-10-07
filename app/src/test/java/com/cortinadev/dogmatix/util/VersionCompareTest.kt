package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.VersionCompare.Category
import com.cortinadev.dogmatix.util.VersionCompare.ReasonKind
import com.cortinadev.dogmatix.util.VersionCompare.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {
    private fun v(name: String, vararg tags: String, size: Long = 0) = Version(name, name, tags.toList(), size)
    private fun pref(regions: List<String>, vararg languages: String) = VersionPreference(languages = languages.toList(), regions = regions)
    private val dutch = VersionPreferences.defaultFor(setOf("NL", "EN"), "nl", "nl")
    private val english = VersionPreferences.defaultFor(setOf("EN"), "en", "en")
    private fun best(p: VersionPreference, vararg list: Version) = VersionCompare.rank(list.toList(), p).first().version.name
    private fun reasons(p: VersionPreference, list: List<Version>, name: String) = VersionCompare.rank(list, p).first { it.version.name == name }.reasons

    // ---- Regions only from tags -------------------------------------------------------------------

    @Test fun `region words in the title never count`() {
        val p = pref(listOf("World", "USA", "Japan", "Europe"))
        val region = { name: String -> reasons(p, listOf(v(name)), name).first { it.category == Category.REGION } }
        assertEquals(listOf("Japan"), region("Super Mario World (Japan).sfc").params)
        assertEquals(listOf("Japan"), region("World Heroes (Japan).neo").params)
        assertEquals(listOf("USA"), region("Street Fighter II - The World Warrior (USA).sfc").params)
        assertEquals(ReasonKind.REGION_NONE, reasons(pref(listOf("France")), listOf(v("Tour de France (Europe).sfc")), "Tour de France (Europe).sfc").first { it.category == Category.REGION }.kind)
        assertEquals("Super Mario World (USA).sfc", best(english, v("Super Mario World (Japan).sfc"), v("Super Mario World (USA).sfc")))
    }

    @Test fun `regions from the database tags and grouped tags count`() {
        val p = pref(listOf("Europe", "USA"))
        assertEquals("Asterix.gba", best(p, v("Asterix 2.gba", "USA"), v("Asterix.gba", "Europe")))
        assertEquals(listOf("Europe"), reasons(p, listOf(v("G (USA, Europe).gba")), "G (USA, Europe).gba").first { it.kind == ReasonKind.REGION_MATCH }.params)
    }

    // ---- Languages --------------------------------------------------------------------------------

    @Test fun `a language match weighs less than one region step`() {
        val p = pref(listOf("France", "Germany"), "DE")
        assertEquals("G (France).sfc", best(p, v("G (Germany) (De).sfc"), v("G (France).sfc")))
        val weights = VersionCompare.rank(listOf(v("G (Europe) (Nl,En,De).gba"), v("G (Europe) (Nl).gba")), pref(emptyList(), "NL", "EN", "DE"))
            .flatMap { it.reasons }.filter { it.kind == ReasonKind.LANGUAGE_MATCH }.map { it.weight }
        assertTrue(weights.all { it in 1..9 })
    }

    @Test fun `untagged english speaking releases are english`() {
        listOf("USA", "UK", "Australia", "Canada", "World").forEach { region ->
            val name = "G ($region).gba"
            val r = reasons(english, listOf(v(name)), name).first { it.category == Category.LANGUAGE }
            assertEquals(region, ReasonKind.LANGUAGE_ASSUMED, r.kind)
            assertEquals(listOf("EN"), r.params)
            assertTrue(r.weight > 0)
        }
        // A tag says otherwise: the tag wins.
        assertEquals(ReasonKind.LANGUAGE_UNREAD, reasons(english, listOf(v("G (USA) (Es).gba")), "G (USA) (Es).gba").first { it.category == Category.LANGUAGE }.kind)
    }

    @Test fun `a release only in languages the user does not read goes down`() {
        val list = listOf(v("G (Europe) (Fr,De).gba"), v("G (USA).gba"))
        assertEquals("G (USA).gba", VersionCompare.rank(list, dutch).first().version.name)
        val unread = reasons(dutch, list, "G (Europe) (Fr,De).gba").first { it.category == Category.LANGUAGE }
        assertEquals(ReasonKind.LANGUAGE_UNREAD, unread.kind)
        assertEquals(listOf("FR", "DE"), unread.params)
        assertTrue(unread.negative)
    }

    @Test fun `a first choice language beats a second choice in the same region`() {
        assertEquals("G (Europe) (Nl).gba", best(dutch, v("G (Europe) (En).gba"), v("G (Europe) (Nl).gba")))
    }

    @Test fun `url encoded names are read decoded and a plain plus stays`() {
        assertEquals("Game%20%28Europe%29%20%28Nl%29.gba", best(dutch, v("Game (USA).gba"), v("Game%20%28Europe%29%20%28Nl%29.gba")))
        val facts = VersionCompare.factsOf(v("Game (En+Fr).gba"))
        assertEquals(listOf("EN", "FR"), facts.languages)
    }

    // ---- Releases, dumps, revisions --------------------------------------------------------------

    @Test fun `final verified and newest win and the reasons say so`() {
        val list = listOf(v("G (USA) (Beta).sfc"), v("G (USA).sfc"), v("G (USA) (Rev 1) [!].sfc"), v("G (USA) [b1].sfc"))
        val ranked = VersionCompare.rank(list, english)
        assertEquals("G (USA) (Rev 1) [!].sfc", ranked.first().version.name)
        assertTrue(ranked.first().reasons.any { it.kind == ReasonKind.DUMP_VERIFIED })
        assertTrue(ranked.first().reasons.any { it.kind == ReasonKind.REVISION_NEWEST })
        assertTrue(reasons(english, list, "G (USA) (Beta).sfc").any { it.kind == ReasonKind.PRE_RELEASE && it.negative })
        assertTrue(reasons(english, list, "G (USA) [b1].sfc").any { it.kind == ReasonKind.DUMP_BAD && it.negative })
    }

    @Test fun `switched off rules weigh nothing`() {
        val p = english.copy(preferFinal = false, preferLatestRevision = false)
        val ranked = VersionCompare.rank(listOf(v("G (USA) (Beta).sfc"), v("G (USA) (Rev 1).sfc")), p)
        assertTrue(ranked.flatMap { it.reasons }.none { it.category == Category.RELEASE || it.category == Category.REVISION })
    }

    @Test fun `the size decides a tie only when asked`() {
        val list = listOf(v("G (USA).gba", size = 200), v("G (USA) .gba", size = 100))
        assertEquals("G (USA) .gba", VersionCompare.rank(list, english.copy(sizeTieBreak = SizeTieBreak.SMALLER)).first().version.name)
        assertEquals("G (USA).gba", VersionCompare.rank(list, english.copy(sizeTieBreak = SizeTieBreak.LARGER)).first().version.name)
    }

    // ---- Explanations ----------------------------------------------------------------------------

    @Test fun `the best says why and every other version why it is lower`() {
        val e = VersionCompare.explainBest(VersionCompare.rank(listOf(v("G (Japan).gba"), v("G (USA).gba"), v("G (USA) (Demo).gba")), english))!!
        assertEquals("G (USA).gba", e.best.version.name)
        assertEquals(setOf(Category.LANGUAGE, Category.REGION), e.deciding.map { it.category }.toSet())
        assertEquals(2, e.whyNot.size)
        val demo = e.whyNot.first { it.version.version.name == "G (USA) (Demo).gba" }
        assertEquals(ReasonKind.DEMO, demo.gap!!.loser!!.kind)
        assertNull(VersionCompare.explainBest(emptyList()))
    }

    // ---- Preferring versions like one --------------------------------------------------------------

    @Test fun `preferring a version puts its region first and keeps the users languages first`() {
        val facts = VersionCompare.factsOf(v("G (Japan) (Ja).gba"))
        val pinned = VersionCompare.pin(facts, dutch)
        assertEquals("Japan", pinned.regions.first())
        assertEquals(dutch.regions.size, pinned.regions.size)
        assertEquals(listOf("NL", "EN", "JA"), pinned.languages)
        assertEquals(dutch.languages, VersionCompare.pin(VersionCompare.factsOf(v("G (USA).gba")), dutch).languages)
    }

    @Test fun `the plan for all consoles warns when the console order hides it and can clear that part`() {
        val facts = VersionCompare.factsOf(v("G (USA).gba"))
        val own = ConsoleOverride(regions = listOf("Japan", "Europe"), languages = listOf("JA"))
        val plan = VersionCompare.planPin(facts, dutch, own, forConsole = false, followRevisionRule = false, isNewestRevision = true, clearOverride = false)
        assertTrue(plan.hiddenByOverride)
        assertEquals(own, plan.override)
        assertEquals("USA", plan.global.regions.first())
        val cleared = VersionCompare.planPin(facts, dutch, own, forConsole = false, followRevisionRule = false, isNewestRevision = true, clearOverride = true)
        assertFalse(cleared.hiddenByOverride)
        assertTrue(cleared.clearsOverride)
        // Only the hiding list goes: the languages did not change, so the console keeps its own.
        assertEquals(ConsoleOverride(regions = null, languages = listOf("JA")), cleared.override)
        assertFalse(VersionCompare.planPin(facts, dutch, null, false, false, true, false).hiddenByOverride)
    }

    @Test fun `the plan for one console stores only the list that changed and leaves the rules alone`() {
        val facts = VersionCompare.factsOf(v("G (USA) (Rev 1).gba"))
        val plan = VersionCompare.planPin(facts, dutch, null, forConsole = true, followRevisionRule = true, isNewestRevision = false, clearOverride = true)
        assertEquals(dutch, plan.global)
        assertEquals(null, plan.override!!.languages)
        assertEquals("USA", plan.override!!.regions!!.first())
        assertEquals(dutch.preferLatestRevision, plan.shown.preferLatestRevision)
        // An existing own list that does not change stays.
        val own = ConsoleOverride(languages = listOf("EN"))
        assertEquals(listOf("EN"), VersionCompare.planPin(facts, dutch, own, true, false, true, false).override!!.languages)
    }

    @Test fun `the revision rule follows the version only for all consoles when asked`() {
        val facts = VersionCompare.factsOf(v("G (USA).gba"))
        assertFalse(VersionCompare.planPin(facts, english, null, false, true, isNewestRevision = false, clearOverride = false).global.preferLatestRevision)
        assertTrue(VersionCompare.planPin(facts, english, null, false, false, isNewestRevision = false, clearOverride = false).global.preferLatestRevision)
    }
}
