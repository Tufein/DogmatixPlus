package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.BetterVersions.Integrity
import com.cortinadev.dogmatix.util.BetterVersions.Offer
import com.cortinadev.dogmatix.util.BetterVersions.OwnedGame
import com.cortinadev.dogmatix.util.BetterVersions.PreKind
import com.cortinadev.dogmatix.util.BetterVersions.Problem
import com.cortinadev.dogmatix.util.BetterVersions.Reason
import com.cortinadev.dogmatix.util.BetterVersions.Scheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BetterVersionsTest {

    private fun up(current: String, candidate: String, dat: DatStatus? = null) = BetterVersions.upgrade(current, candidate, dat)
    private fun reason(current: String, candidate: String, dat: DatStatus? = null) = up(current, candidate, dat)?.reason

    // ---- Parsing -----------------------------------------------------------------------------

    @Test fun `a plain No-Intro name has only identity tags`() {
        val p = BetterVersions.parse("Super Mario World (USA).sfc")
        assertEquals("Super Mario World (USA)", p.base)
        assertEquals(setOf("usa"), p.identity)
        assertEquals(Integrity.GOOD, p.integrity)
        assertNull(p.pre)
        assertNull(p.revision)
    }

    @Test fun `regions and languages are separate comma lists but one identity`() {
        val p = BetterVersions.parse("Super Mario 64 (Europe) (En,Fr,De).z64")
        assertEquals(setOf("europe", "en", "fr", "de"), p.identity)
        assertEquals(setOf("usa", "europe"), BetterVersions.parse("Pokemon - Red Version (USA, Europe) (SGB Enhanced).zip").identity - "sgb enhanced")
    }

    @Test fun `revisions with numbers and letters`() {
        val one = BetterVersions.parse("Tetris (World) (Rev 1).gb").revision!!
        assertEquals(Scheme.REV, one.scheme)
        assertEquals(listOf(1), one.parts)
        assertEquals("Rev 1", one.label)
        val a = BetterVersions.parse("Mario Kart 64 (Europe) (Rev A).z64").revision!!
        assertEquals(listOf(1), a.parts)
        assertEquals("Rev A", a.label)
        assertEquals(listOf(2), BetterVersions.parse("Game (USA) (Rev B)").revision!!.parts)
        assertEquals(listOf(1, 1), BetterVersions.parse("Game (USA) (Rev 1.1)").revision!!.parts)
    }

    @Test fun `versions in tags, in GoodTools style and at the end of the title`() {
        val v = BetterVersions.parse("Gran Turismo 2 (USA) (v1.1)").revision!!
        assertEquals(Scheme.VERSION, v.scheme)
        assertEquals(listOf(1, 1), v.parts)
        assertEquals("v1.1", v.label)
        assertEquals(listOf(1, 1), BetterVersions.parse("Sonic The Hedgehog (UE) (V1.1) [!]").revision!!.parts)
        assertEquals(listOf(2, 0), BetterVersions.parse("Game (USA) (Ver 2.0)").revision!!.parts)
        assertEquals(listOf(1, 2), BetterVersions.parse("Some Game v1.2").revision!!.parts)
        assertEquals(listOf(1, 2), BetterVersions.parse("Some Game v1.2 (USA)").revision!!.parts)
    }

    @Test fun `a version at the end of a bare title is not taken for an extension`() {
        assertEquals("Some Game v1.1", BetterVersions.withoutExtension("Some Game v1.1"))
        assertEquals("Dr. Mario (USA)", BetterVersions.withoutExtension("Dr. Mario (USA).nes"))
        assertEquals("Mr. Driller", BetterVersions.withoutExtension("Mr. Driller"))
        assertEquals("Star Fox (USA) (v1.1)", BetterVersions.withoutExtension("Star Fox (USA) (v1.1).32x"))
        assertEquals("Game (USA)", BetterVersions.withoutExtension("Game (USA).7z"))
    }

    @Test fun `pre-release builds of every kind`() {
        assertEquals(PreKind.BETA, BetterVersions.parse("Chrono Trigger (USA) (Beta)").pre!!.kind)
        assertEquals(PreKind.BETA, BetterVersions.parse("Chrono Trigger (USA) (Beta 3)").pre!!.kind)
        assertEquals(PreKind.PROTO, BetterVersions.parse("Chrono Trigger (USA) (Proto)").pre!!.kind)
        assertEquals(PreKind.PROTO, BetterVersions.parse("Chrono Trigger (USA) (Possible Proto)").pre!!.kind)
        assertEquals(PreKind.DEMO, BetterVersions.parse("Chrono Trigger (USA) (Demo)").pre!!.kind)
        assertEquals(PreKind.SAMPLE, BetterVersions.parse("Chrono Trigger (USA) (Sample)").pre!!.kind)
        assertEquals(PreKind.KIOSK, BetterVersions.parse("Chrono Trigger (USA) (Kiosk)").pre!!.kind)
        assertEquals(PreKind.DEMO, BetterVersions.parse("Chrono Trigger (Japan) (Taikenban)").pre!!.kind)
        assertNull(BetterVersions.parse("Chrono Trigger (USA) (Rev 1)").pre)
    }

    @Test fun `bad dump flags of GoodTools and No-Intro`() {
        assertEquals(Problem.BAD_DUMP, BetterVersions.parse("Sonic The Hedgehog (UE) [b1]").problem)
        assertEquals(Problem.BAD_DUMP, BetterVersions.parse("Sonic The Hedgehog (UE) [b]").problem)
        assertEquals(Problem.OVERDUMP, BetterVersions.parse("Sonic The Hedgehog (UE) [o1]").problem)
        assertEquals(Problem.BAD_DUMP, BetterVersions.parse("Sonic The Hedgehog (UE) (Bad Dump)").problem)
        assertEquals(Problem.OVERDUMP, BetterVersions.parse("Sonic The Hedgehog (UE) (Overdump)").problem)
        assertEquals(Integrity.BAD, BetterVersions.parse("Sonic The Hedgehog (UE) [o1]").integrity)
    }

    @Test fun `hack, pirate and trained flags are modified dumps`() {
        assertEquals(Problem.HACK, BetterVersions.parse("Contra (U) [h1C]").problem)
        assertEquals(Problem.HACK, BetterVersions.parse("Contra (U) [hI]").problem)
        assertEquals(Problem.HACK, BetterVersions.parse("Contra (USA) (Hack)").problem)
        assertEquals(Problem.PIRATE, BetterVersions.parse("Contra (U) [p1]").problem)
        assertEquals(Problem.PIRATE, BetterVersions.parse("Contra (World) (Pirate)").problem)
        assertEquals(Problem.MODIFIED, BetterVersions.parse("Contra (U) [t1]").problem)
        assertEquals(Problem.MODIFIED, BetterVersions.parse("Contra (U) [f1]").problem)
        assertEquals(Integrity.MODIFIED, BetterVersions.parse("Contra (U) [h1C]").integrity)
    }

    @Test fun `the worst flag wins`() {
        val p = BetterVersions.parse("Game (U) [h1][b1]")
        assertEquals(Integrity.BAD, p.integrity)
        assertEquals(Problem.BAD_DUMP, p.problem)
    }

    @Test fun `verified mark is not a problem`() {
        val p = BetterVersions.parse("Sonic The Hedgehog (UE) [!]")
        assertTrue(p.verified)
        assertEquals(Integrity.GOOD, p.integrity)
        assertEquals(setOf("ue"), p.identity)
    }

    @Test fun `translations and alternates stay part of the identity`() {
        assertTrue("[t+eng" in BetterVersions.parse("Final Fantasy III (J) [T+Eng1.1]").identity.map { it.take(6) })
        assertTrue(BetterVersions.parse("Game (U) [a1]").identity.any { it.startsWith("[a1") })
        assertEquals(Integrity.GOOD, BetterVersions.parse("Game (U) [T+Eng]").integrity)
    }

    @Test fun `color and super game boy marks are not hacks`() {
        assertEquals(Integrity.GOOD, BetterVersions.parse("Pokemon Red (UE) [S][!]").integrity)
        assertEquals(Integrity.GOOD, BetterVersions.parse("Tetris DX (U) [C][!]").integrity)
    }

    @Test fun `updates and DLC are marked as add-ons`() {
        assertTrue(BetterVersions.parse("Game (Update)").addOn)
        assertTrue(BetterVersions.parse("Game [DLC]").addOn)
        assertTrue(BetterVersions.parse("Game (Patch 1.1)").addOn)
        assertFalse(BetterVersions.parse("Game (USA)").addOn)
    }

    @Test fun `disc numbers are not part of the identity`() {
        assertEquals(setOf("usa"), BetterVersions.parse("Final Fantasy VII (USA) (Disc 1) (Rev 1)").identity)
        assertEquals(setOf("usa"), BetterVersions.parse("Final Fantasy VII (USA) (Disc 1 of 3)").identity)
    }

    // ---- Newer revision ----------------------------------------------------------------------

    @Test fun `a revision beats the original`() {
        val u = up("Tetris (World).gb", "Tetris (World) (Rev 1).gb")!!
        assertEquals(Reason.NEWER_REVISION, u.reason)
        assertEquals("Rev 1", u.revision)
        assertEquals(Reason.NEWER_REVISION, reason("Mario Kart 64 (Europe).z64", "Mario Kart 64 (Europe) (Rev A).z64"))
        assertEquals("Rev A", up("Mario Kart 64 (Europe).z64", "Mario Kart 64 (Europe) (Rev A).z64")!!.revision)
    }

    @Test fun `later revisions beat earlier ones`() {
        assertEquals(Reason.NEWER_REVISION, reason("Star Fox 64 (USA) (Rev 1).z64", "Star Fox 64 (USA) (Rev 2).z64"))
        assertEquals(Reason.NEWER_REVISION, reason("Super Mario 64 (Japan) (Rev A).z64", "Super Mario 64 (Japan) (Rev B).z64"))
        assertEquals(Reason.NEWER_REVISION, reason("Game (USA) (Rev 1).zip", "Game (USA) (Rev 1.1).zip"))
    }

    @Test fun `the same or an older revision is never better`() {
        assertNull(up("Star Fox 64 (USA) (Rev 1).z64", "Star Fox 64 (USA) (Rev 1).7z"))
        assertNull(up("Star Fox 64 (USA) (Rev 2).z64", "Star Fox 64 (USA) (Rev 1).z64"))
        assertNull(up("Star Fox 64 (USA) (Rev 1).z64", "Star Fox 64 (USA).z64"))
        assertNull(up("Super Mario 64 (Japan) (Rev B).z64", "Super Mario 64 (Japan) (Rev A).z64"))
    }

    @Test fun `Rev A and Rev 1 are the same revision`() {
        assertNull(up("Game (USA) (Rev A).zip", "Game (USA) (Rev 1).zip"))
    }

    @Test fun `versions compare by number`() {
        assertEquals(Reason.NEWER_REVISION, reason("Gran Turismo 2 (USA) (v1.0).chd", "Gran Turismo 2 (USA) (v1.1).chd"))
        assertEquals("v1.1", up("Gran Turismo 2 (USA) (v1.0).chd", "Gran Turismo 2 (USA) (v1.1).chd")!!.revision)
        assertEquals(Reason.NEWER_REVISION, reason("Sonic The Hedgehog (UE) (V1.0).zip", "Sonic The Hedgehog (UE) (V1.1).zip"))
        assertEquals(Reason.NEWER_REVISION, reason("Game (USA) (v1.9).zip", "Game (USA) (v1.10).zip"))
        assertEquals(Reason.NEWER_REVISION, reason("Game (USA) (v1.1).zip", "Game (USA) (v2.0).zip"))
        assertNull(up("Game (USA) (v1.10).zip", "Game (USA) (v1.9).zip"))
    }

    @Test fun `an untagged file counts as version 1_0`() {
        assertEquals(Reason.NEWER_REVISION, reason("Gran Turismo 2 (USA).chd", "Gran Turismo 2 (USA) (v1.1).chd"))
        assertNull(up("Gran Turismo 2 (USA).chd", "Gran Turismo 2 (USA) (v1.0).chd"))
        assertNull(up("Gran Turismo 2 (USA) (v1.1).chd", "Gran Turismo 2 (USA).chd"))
    }

    @Test fun `revisions and versions are not compared with each other`() {
        assertNull(up("Game (USA) (Rev 1).zip", "Game (USA) (v1.2).zip"))
        assertNull(up("Game (USA) (v1.2).zip", "Game (USA) (Rev 2).zip"))
    }

    @Test fun `a version at the end of the title counts`() {
        assertEquals(Reason.NEWER_REVISION, reason("Some Game v1.0 (USA).zip", "Some Game v1.1 (USA).zip"))
    }

    // ---- Final instead of beta ----------------------------------------------------------------

    @Test fun `a final release beats a beta, prototype, demo, sample or kiosk build`() {
        for ((tag, kind) in listOf("Beta" to PreKind.BETA, "Proto" to PreKind.PROTO, "Demo" to PreKind.DEMO, "Sample" to PreKind.SAMPLE, "Kiosk" to PreKind.KIOSK)) {
            val u = up("Chrono Trigger (USA) ($tag).sfc", "Chrono Trigger (USA).sfc")!!
            assertEquals(Reason.FINAL_RELEASE, u.reason)
            assertEquals(kind, u.pre)
        }
    }

    @Test fun `a revision of the final also beats a beta`() {
        assertEquals(Reason.FINAL_RELEASE, reason("Chrono Trigger (USA) (Beta).sfc", "Chrono Trigger (USA) (Rev 1).sfc"))
        assertEquals(Reason.FINAL_RELEASE, reason("Chrono Trigger (USA) (Rev 2) (Beta).sfc", "Chrono Trigger (USA).sfc"))
    }

    @Test fun `a final game is never traded for an unfinished one`() {
        assertNull(up("Chrono Trigger (USA).sfc", "Chrono Trigger (USA) (Beta).sfc"))
        assertNull(up("Chrono Trigger (USA) (Rev 1).sfc", "Chrono Trigger (USA) (Demo).sfc"))
        assertNull(up("Chrono Trigger (USA).sfc", "Chrono Trigger (USA) (Rev 1) (Proto).sfc"))
    }

    @Test fun `two different unfinished builds do not replace each other`() {
        assertNull(up("Chrono Trigger (USA) (Beta).sfc", "Chrono Trigger (USA) (Proto).sfc"))
        assertNull(up("Chrono Trigger (USA) (Beta 1).sfc", "Chrono Trigger (USA) (Beta 2).sfc"))
        assertNull(up("Chrono Trigger (USA) (Demo).sfc", "Chrono Trigger (USA) (Sample).sfc"))
    }

    // ---- Good dump instead of a bad one -------------------------------------------------------

    @Test fun `a good dump beats a bad dump`() {
        val u = up("Sonic The Hedgehog (UE) [b1].zip", "Sonic The Hedgehog (UE) [!].zip")!!
        assertEquals(Reason.GOOD_DUMP, u.reason)
        assertEquals(Problem.BAD_DUMP, u.problem)
        assertEquals(Reason.GOOD_DUMP, reason("Sonic The Hedgehog (UE) [b1].zip", "Sonic The Hedgehog (UE).zip"))
    }

    @Test fun `a good dump beats an overdump, a hack and a pirate copy`() {
        assertEquals(Problem.OVERDUMP, up("Contra (U) [o1].nes", "Contra (U) [!].nes")!!.problem)
        assertEquals(Problem.HACK, up("Contra (U) [h1C].nes", "Contra (U) [!].nes")!!.problem)
        assertEquals(Problem.PIRATE, up("Contra (World) (Pirate).nes", "Contra (World).nes")!!.problem)
        assertEquals(Problem.HACK, up("Contra (USA) (Hack).nes", "Contra (USA).nes")!!.problem)
        assertEquals(Problem.BAD_DUMP, up("Contra (USA) (Bad Dump).nes", "Contra (USA) (Rev 1).nes")!!.problem)
    }

    @Test fun `a flagged file is never offered`() {
        assertNull(up("Sonic The Hedgehog (UE).zip", "Sonic The Hedgehog (UE) [b1].zip"))
        assertNull(up("Sonic The Hedgehog (UE) [b1].zip", "Sonic The Hedgehog (UE) [b2].zip"))
        assertNull(up("Sonic The Hedgehog (UE) [o1].zip", "Sonic The Hedgehog (UE) [h1].zip"))
        assertNull(up("Contra (USA).nes", "Contra (USA) (Rev 1) (Pirate).nes"))
        assertNull(up("Contra (USA) (Rev 1).nes", "Contra (USA) (Rev 2) [h1].nes"))
    }

    @Test fun `a good dump of an older revision does not replace a newer bad dump`() {
        assertNull(up("Game (USA) (Rev 2) [b1].zip", "Game (USA) (Rev 1).zip"))
        assertEquals(Reason.GOOD_DUMP, reason("Game (USA) (Rev 1) [b1].zip", "Game (USA) (Rev 2).zip"))
    }

    @Test fun `verified mark alone is not an upgrade`() {
        assertNull(up("Sonic The Hedgehog (UE).zip", "Sonic The Hedgehog (UE) [!].zip"))
    }

    // ---- Never a different region, language, title ---------------------------------------------

    @Test fun `a different region is never better`() {
        assertNull(up("Super Mario World (USA).sfc", "Super Mario World (Europe) (Rev 1).sfc"))
        assertNull(up("Super Mario World (Japan) (Beta).sfc", "Super Mario World (USA).sfc"))
        assertNull(up("Super Mario World (Europe) [b1].sfc", "Super Mario World (USA).sfc"))
        assertNull(up("Sonic The Hedgehog (UE).zip", "Sonic The Hedgehog (J) (Rev 1).zip"))
    }

    @Test fun `a different region list is a different release`() {
        assertNull(up("Pokemon - Red Version (USA).gb", "Pokemon - Red Version (USA, Europe).gb"))
        assertNull(up("Pokemon - Red Version (USA, Europe).gb", "Pokemon - Red Version (USA, Europe, Korea).gb"))
        assertNull(up("Pokemon - Red Version (USA, Europe).gb", "Pokemon - Red Version (USA).gb"))
    }

    @Test fun `the order of regions does not matter`() {
        assertEquals(Reason.NEWER_REVISION, reason("Game (USA, Europe).gb", "Game (Europe, USA) (Rev 1).gb"))
    }

    @Test fun `a different language is never better`() {
        assertNull(up("Super Mario 64 (Europe) (En,Fr,De).z64", "Super Mario 64 (Europe) (En,Fr,De,Es,It) (Rev 1).z64"))
        assertNull(up("Game (Japan) (Ja).zip", "Game (Japan) (En) (Rev 1).zip"))
        assertNull(up("Game (Europe) (Beta).zip", "Game (Europe) (En,Fr,De).zip"))
        assertEquals(Reason.NEWER_REVISION, reason("Super Mario 64 (Europe) (En,Fr,De).z64", "Super Mario 64 (Europe) (En,Fr,De) (Rev 1).z64"))
    }

    @Test fun `translations and other editions are different releases`() {
        assertNull(up("Final Fantasy III (J).sfc", "Final Fantasy III (J) [T+Eng1.1].sfc"))
        assertNull(up("Final Fantasy III (J) [T+Eng1.0].sfc", "Final Fantasy III (J) [T+Eng1.1].sfc"))
        assertNull(up("Game (USA).zip", "Game (USA) (Virtual Console).zip"))
        assertNull(up("Game (USA) (Beta).zip", "Game (USA) (Virtual Console).zip"))
        assertNull(up("Game (USA) [b1].zip", "Game (USA) (Alt).zip"))
        assertNull(up("Game (USA).zip", "Game (USA) (Unl).zip"))
        assertNull(up("Game (USA) (Rev 1).zip", "Game (USA) (Rev 2) (Greatest Hits).zip"))
        assertNull(up("Pokemon - Red Version (USA).gb", "Pokemon - Red Version (USA) (SGB Enhanced) (Rev 1).gb"))
    }

    @Test fun `another game of the same series is not the same title`() {
        assertNull(up("Super Mario World (USA).sfc", "Super Mario World 2 - Yoshi's Island (USA) (Rev 1).sfc"))
        assertNull(up("Sonic The Hedgehog (UE) [b1].zip", "Sonic The Hedgehog 2 (UE) [!].zip"))
        assertNull(up("Mario Kart 64 (USA).z64", "Mario Kart 64 (USA) (Rev 1) (Beta).z64"))
        assertNull(up("Final Fantasy (USA).nes", "Final Fantasy II (USA) (Rev 1).nes"))
    }

    @Test fun `article order and accents do not matter for the title`() {
        assertEquals(
            Reason.NEWER_REVISION,
            reason("Legend of Zelda, The - Ocarina of Time (USA).z64", "Legend of Zelda, The - Ocarina of Time (USA) (Rev 1).z64")
        )
        assertEquals(Reason.NEWER_REVISION, reason("The Legend of Zelda (USA).nes", "Legend of Zelda, The (USA) (Rev 1).nes"))
        assertEquals(Reason.NEWER_REVISION, reason("Pokémon Gold (USA).gbc", "Pokemon Gold (USA) (Rev 1).gbc"))
    }

    @Test fun `titles with no readable letters are never matched`() {
        assertNull(up("ドラゴンクエスト (Japan).sfc", "ドラゴンクエスト (Japan) (Rev 1).sfc"))
    }

    // ---- Discs ---------------------------------------------------------------------------------

    @Test fun `another disc is never a better version of this disc`() {
        assertNull(up("Final Fantasy VII (USA) (Disc 1).chd", "Final Fantasy VII (USA) (Disc 2) (Rev 1).chd"))
        assertNull(up("Final Fantasy VII (USA) (Disc 1) (Beta).chd", "Final Fantasy VII (USA) (Disc 2).chd"))
        assertNull(up("Final Fantasy VII Disc 1 (Beta).chd", "Final Fantasy VII Disc 2.chd"))
        assertNull(up("Final Fantasy VII (USA) (Disc 1) [b1].chd", "Final Fantasy VII (USA) (Disc 2).chd"))
        assertNull(up("Game (USA) (Side A) (Beta).dsk", "Game (USA) (Side B).dsk"))
    }

    @Test fun `the same disc of a better release is found`() {
        assertEquals(Reason.NEWER_REVISION, reason("Final Fantasy VII (USA) (Disc 1).chd", "Final Fantasy VII (USA) (Disc 1) (Rev 1).chd"))
        assertEquals(Reason.NEWER_REVISION, reason("Final Fantasy VII (USA) (Disc 2).chd", "Final Fantasy VII (USA) (Disc 2 of 3) (v1.1).chd"))
        assertEquals(Reason.FINAL_RELEASE, reason("Final Fantasy VII (USA) (Disc 3) (Demo).chd", "Final Fantasy VII (USA) (Disc 3).chd"))
    }

    // ---- Add-ons, identical names, other cases --------------------------------------------------

    @Test fun `updates and DLC are never compared`() {
        assertNull(up("Game (USA) (Update).nsp", "Game (USA) (Rev 1).nsp"))
        assertNull(up("Game (USA).nsp", "Game (USA) (Update).nsp"))
        assertNull(up("Game (USA).nsp", "Game (USA) [DLC].nsp"))
    }

    @Test fun `the same file under another extension or case is not better`() {
        assertNull(up("Super Mario World (USA)", "Super Mario World (USA).zip"))
        assertNull(up("super mario world (usa)", "Super Mario World (USA).sfc"))
    }

    @Test fun `names with no tags at all`() {
        assertEquals(Reason.NEWER_REVISION, reason("Tetris.gb", "Tetris (Rev 1).gb"))
        assertNull(up("Tetris.gb", "Tetris (World) (Rev 1).gb"))
        assertNull(up("Tetris.gb", "Tetris.zip"))
    }

    @Test fun `case of tags does not matter`() {
        assertEquals(Reason.GOOD_DUMP, reason("Contra (U) [B1].nes", "Contra (U).nes"))
        assertEquals(Reason.NEWER_REVISION, reason("Game (USA).zip", "Game (usa) (REV 1).zip"))
        assertEquals(Reason.FINAL_RELEASE, reason("Game (USA) (BETA).zip", "Game (USA).zip"))
    }

    // ---- DAT status ----------------------------------------------------------------------------

    @Test fun `a file the DAT does not know is replaced by a verified one`() {
        val u = up("Sonic The Hedgehog (UE).zip", "Sonic The Hedgehog (UE) [!].zip", DatStatus.UNKNOWN)!!
        assertEquals(Reason.GOOD_DUMP, u.reason)
        assertEquals(Problem.NOT_IN_DAT, u.problem)
    }

    @Test fun `a file the DAT does not know is not replaced by an unmarked one of the same release`() {
        assertNull(up("Sonic The Hedgehog (UE).zip", "Sonic The Hedgehog (UE).7z", DatStatus.UNKNOWN))
        assertNull(up("Sonic The Hedgehog (UE).zip", "Sonic The Hedgehog (UE) (Beta).zip", DatStatus.UNKNOWN))
        assertNull(up("Sonic The Hedgehog (UE).zip", "Sonic The Hedgehog (Europe) [!].zip", DatStatus.UNKNOWN))
    }

    @Test fun `a newer revision is still found when the DAT does not know the file`() {
        assertEquals(Reason.NEWER_REVISION, reason("Tetris (World).gb", "Tetris (World) (Rev 1).gb", DatStatus.UNKNOWN))
    }

    @Test fun `a file the DAT verified is good whatever its name says`() {
        assertNull(up("Contra (U) [b1].nes", "Contra (U).nes", DatStatus.VERIFIED))
        assertNull(up("Contra (U) [h1C].nes", "Contra (U).nes", DatStatus.MISNAMED))
        assertEquals(Reason.NEWER_REVISION, reason("Contra (U) [b1].nes", "Contra (U) (Rev 1).nes", DatStatus.VERIFIED))
    }

    @Test fun `a skipped DAT check changes nothing`() {
        assertEquals(Reason.GOOD_DUMP, reason("Contra (U) [b1].nes", "Contra (U).nes", DatStatus.SKIPPED))
        assertNull(up("Contra (U).nes", "Contra (U) [!].nes", DatStatus.SKIPPED))
    }

    // ---- Picking the best of several -----------------------------------------------------------

    private fun offer(name: String, console: String = "snes") = Offer(console + "/" + name, console, name)

    @Test fun `the newest revision is picked`() {
        val offers = listOf(offer("Game (USA) (Rev 1).zip"), offer("Game (USA) (Rev 3).zip"), offer("Game (USA) (Rev 2).zip"))
        val (picked, upgrade) = BetterVersions.best("Game (USA).zip", offers)!!
        assertEquals("Game (USA) (Rev 3).zip", picked.fileName)
        assertEquals("Rev 3", upgrade.revision)
    }

    @Test fun `other regions and other titles among the offers are ignored`() {
        val offers = listOf(
            offer("Game (Europe) (Rev 5).zip"), offer("Game 2 (USA) (Rev 9).zip"),
            offer("Game (USA) (Rev 1).zip"), offer("Game (USA) (Beta).zip"), offer("Game (USA) [b1].zip")
        )
        assertEquals("Game (USA) (Rev 1).zip", BetterVersions.best("Game (USA).zip", offers)!!.first.fileName)
    }

    @Test fun `a final is picked over a beta`() {
        val offers = listOf(offer("Game (USA) (Rev 1).zip"), offer("Game (USA) (Rev 2) (Beta).zip"))
        assertEquals("Game (USA) (Rev 1).zip", BetterVersions.best("Game (USA) (Demo).zip", offers)!!.first.fileName)
    }

    @Test fun `nothing better gives nothing`() {
        assertNull(BetterVersions.best("Game (USA) (Rev 2).zip", listOf(offer("Game (USA).zip"), offer("Game (USA) (Rev 1).zip"))))
        assertNull(BetterVersions.best("Game (USA).zip", emptyList()))
    }

    @Test fun `ties go to the name that sorts first`() {
        val offers = listOf(offer("Game (USA) (Rev 1).zip"), offer("Game (USA) (Rev 1).7z"))
        assertEquals("Game (USA) (Rev 1).7z", BetterVersions.best("Game (USA).zip", offers)!!.first.fileName)
    }

    // ---- Matching a library --------------------------------------------------------------------

    private fun owned(name: String, console: String = "snes", dat: DatStatus? = null) = OwnedGame("$console/$name", console, name, dat)

    @Test fun `each game gets its own better version`() {
        val matches = BetterVersions.suggest(
            owned = listOf(owned("Tetris (World)", "gb"), owned("Chrono Trigger (USA) (Beta)"), owned("Super Mario World (USA)")),
            offers = listOf(
                offer("Tetris (World) (Rev 1).zip", "gb"), offer("Chrono Trigger (USA).zip"),
                offer("Super Mario World (USA).zip"), offer("Super Mario World (Europe) (Rev 1).zip")
            )
        )
        assertEquals(listOf("Tetris (World)", "Chrono Trigger (USA) (Beta)"), matches.map { it.owned.name })
        assertEquals(listOf(Reason.NEWER_REVISION, Reason.FINAL_RELEASE), matches.map { it.upgrade.reason })
    }

    @Test fun `offers of another console are never used`() {
        val matches = BetterVersions.suggest(
            listOf(owned("Tetris (World)", "gb")),
            listOf(offer("Tetris (World) (Rev 1).zip", "nes"))
        )
        assertTrue(matches.isEmpty())
    }

    @Test fun `ignored suggestions stay away but another offer still shows`() {
        val owned = listOf(owned("Game (USA)"))
        val first = offer("Game (USA) (Rev 1).zip")
        val ignored = setOf(BetterVersions.ignoreKey("snes", "Game (USA)", first.fileName))
        assertTrue(BetterVersions.suggest(owned, listOf(first), ignored).isEmpty())
        val second = offer("Game (USA) (Rev 2).zip")
        assertEquals("Game (USA) (Rev 2).zip", BetterVersions.suggest(owned, listOf(first, second), ignored).single().offer.fileName)
    }

    @Test fun `ignore keys do not depend on case or extension`() {
        assertEquals(
            BetterVersions.ignoreKey("snes", "Game (USA)", "Game (USA) (Rev 1).zip"),
            BetterVersions.ignoreKey("snes", "GAME (USA).sfc", "game (usa) (rev 1).zip")
        )
        assertTrue(BetterVersions.ignoreKey("snes", "Game (USA)", "x.zip") != BetterVersions.ignoreKey("gb", "Game (USA)", "x.zip"))
    }

    @Test fun `skipped offers are not suggested`() {
        val rev1 = offer("Game (USA) (Rev 1).zip")
        val rev2 = offer("Game (USA) (Rev 2).zip")
        val matches = BetterVersions.suggest(listOf(owned("Game (USA)")), listOf(rev1, rev2), skip = { it.fileName == rev2.fileName })
        assertEquals(rev1.fileName, matches.single().offer.fileName)
    }

    @Test fun `one offer is suggested for one game only`() {
        val matches = BetterVersions.suggest(
            listOf(owned("Game (USA) [b1]"), owned("Game (USA)")),
            listOf(offer("Game (USA) (Rev 1).zip"))
        )
        assertEquals(1, matches.size)
        assertEquals("Game (USA) [b1]", matches.single().owned.name)
    }

    @Test fun `the DAT status of the owned file is used`() {
        val matches = BetterVersions.suggest(
            listOf(owned("Sonic The Hedgehog (UE)", dat = DatStatus.UNKNOWN)),
            listOf(offer("Sonic The Hedgehog (UE) [!].zip"))
        )
        assertEquals(Problem.NOT_IN_DAT, matches.single().upgrade.problem)
    }

    @Test fun `empty inputs give nothing`() {
        assertTrue(BetterVersions.suggest(emptyList(), listOf(offer("a.zip"))).isEmpty())
        assertTrue(BetterVersions.suggest(listOf(owned("Game (USA)")), emptyList()).isEmpty())
    }

    @Test fun `parse never fails on odd names`() {
        for (name in listOf("", "()", "[]", "(", ")", "((USA))", "[[b]]", ".zip", "...", "(Rev )", "(v)", "A (B", "x (Rev 99999999999)")) {
            assertNotNull(BetterVersions.parse(name))
        }
    }
}
