package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.FrontendMetadata.Field
import com.cortinadev.dogmatix.util.FrontendMetadata.GameMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontendMetadataTest {

    private fun text(vararg lines: String) = lines.joinToString("\n") + "\n"

    private val zelda = GameMeta(
        name = "The Legend of Zelda", description = "A hero.", genre = "Adventure",
        released = "1992", developer = "Nintendo", publisher = "Nintendo", ratingPercent = 85
    )

    // ---- ES-DE: creating ---------------------------------------------------------------------

    @Test fun `esde creates a gamelist when there is none`() {
        val m = FrontendMetadata.esdeMerge(null, mapOf("Zelda.gba" to zelda))
        assertEquals(1, m.added)
        assertEquals(
            text(
                "<?xml version=\"1.0\"?>", "<gameList>", "\t<game>", "\t\t<path>./Zelda.gba</path>",
                "\t\t<name>The Legend of Zelda</name>", "\t\t<desc>A hero.</desc>", "\t\t<rating>0.85</rating>",
                "\t\t<releasedate>19920101T000000</releasedate>", "\t\t<developer>Nintendo</developer>",
                "\t\t<publisher>Nintendo</publisher>", "\t\t<genre>Adventure</genre>", "\t</game>", "</gameList>"
            ),
            m.content
        )
    }

    @Test fun `esde treats an empty file like a missing one`() {
        assertEquals(1, FrontendMetadata.esdeMerge("  \n", mapOf("A.gba" to zelda)).added)
    }

    @Test fun `esde makes no entry for a game with only a name`() {
        val m = FrontendMetadata.esdeMerge(null, mapOf("A.gba" to GameMeta(name = "A")))
        assertNull(m.content)
        assertEquals(1, m.skipped)
    }

    @Test fun `esde dates and ratings use the frontend formats`() {
        assertEquals("20041121T000000", FrontendMetadata.esdeDate("2004-11-21"))
        assertEquals("20040101T000000", FrontendMetadata.esdeDate("2004"))
        assertEquals("20041101T000000", FrontendMetadata.esdeDate("2004-11"))
        assertNull(FrontendMetadata.esdeDate("soon"))
        assertNull(FrontendMetadata.esdeDate("1066"))
        assertEquals("0.85", FrontendMetadata.esdeRating(85))
        assertEquals("0.8", FrontendMetadata.esdeRating(80))
        assertEquals("1", FrontendMetadata.esdeRating(100))
    }

    @Test fun `esde writes nothing for a date that is not one`() {
        val m = FrontendMetadata.esdeMerge(null, mapOf("A.gba" to GameMeta(description = "x", released = "tba")))
        assertFalse(m.content!!.contains("releasedate"))
    }

    // ---- ES-DE: merging ------------------------------------------------------------------------

    private val played = text(
        "<?xml version=\"1.0\"?>", "<gameList>", "\t<game>", "\t\t<path>./Zelda.gba</path>", "\t\t<name>Zelda</name>",
        "\t\t<playcount>3</playcount>", "\t\t<favorite>true</favorite>", "\t</game>", "</gameList>"
    )

    @Test fun `esde fills empty fields after the existing ones and keeps everything else`() {
        val m = FrontendMetadata.esdeMerge(played, mapOf("Zelda.gba" to zelda))
        assertEquals(1, m.filled)
        assertEquals(6, m.fields)
        assertEquals(
            text(
                "<?xml version=\"1.0\"?>", "<gameList>", "\t<game>", "\t\t<path>./Zelda.gba</path>", "\t\t<name>Zelda</name>",
                "\t\t<playcount>3</playcount>", "\t\t<favorite>true</favorite>", "\t\t<desc>A hero.</desc>",
                "\t\t<genre>Adventure</genre>", "\t\t<releasedate>19920101T000000</releasedate>",
                "\t\t<developer>Nintendo</developer>", "\t\t<publisher>Nintendo</publisher>", "\t\t<rating>0.85</rating>",
                "\t</game>", "</gameList>"
            ),
            m.content
        )
    }

    @Test fun `esde never overwrites a field that has a value`() {
        val existing = text("<gameList>", "\t<game>", "\t\t<path>./A.gba</path>", "\t\t<desc>Mine</desc>", "\t\t<genre>RPG</genre>", "\t</game>", "</gameList>")
        val m = FrontendMetadata.esdeMerge(existing, mapOf("A.gba" to zelda))
        val out = m.content!!
        assertTrue(out.contains("<desc>Mine</desc>"))
        assertTrue(out.contains("<genre>RPG</genre>"))
        assertFalse(out.contains("A hero."))
        assertFalse(out.contains("Adventure"))
        assertTrue(out.contains("<developer>Nintendo</developer>"))
    }

    @Test fun `esde is idempotent`() {
        val once = FrontendMetadata.esdeMerge(played, mapOf("Zelda.gba" to zelda)).content!!
        val twice = FrontendMetadata.esdeMerge(once, mapOf("Zelda.gba" to zelda))
        assertNull(twice.content)
        assertEquals(1, twice.unchanged)
        assertEquals(0, twice.fields)
    }

    @Test fun `esde fills empty tags in place whatever way they are empty`() {
        val existing = text(
            "<gameList>", "\t<game>", "\t\t<path>./A.gba</path>", "\t\t<desc/>", "\t\t<genre></genre>", "\t\t<developer>  </developer>",
            "\t\t<releasedate>19700101T010000</releasedate>", "\t\t<rating>0</rating>", "\t\t<playcount>1</playcount>", "\t</game>", "</gameList>"
        )
        val out = FrontendMetadata.esdeMerge(existing, mapOf("A.gba" to zelda)).content!!
        assertEquals(
            text(
                "<gameList>", "\t<game>", "\t\t<path>./A.gba</path>", "\t\t<desc>A hero.</desc>", "\t\t<genre>Adventure</genre>",
                "\t\t<developer>Nintendo</developer>", "\t\t<releasedate>19920101T000000</releasedate>", "\t\t<rating>0.85</rating>",
                "\t\t<playcount>1</playcount>", "\t\t<name>The Legend of Zelda</name>", "\t\t<publisher>Nintendo</publisher>", "\t</game>", "</gameList>"
            ),
            out
        )
    }

    @Test fun `esde keeps unknown tags, attributes, comments and other entries as they are`() {
        val existing = text(
            "<?xml version=\"1.0\"?>",
            "<!-- my list -->",
            "<gameList>",
            "  <provider><System>gba</System><software>x</software></provider>",
            "  <game id=\"12\" source=\"ScreenScraper.fr\">",
            "    <path>./B.gba</path>",
            "    <extra a=\"1\"><deep>keep</deep></extra>",
            "    <image>./media/B.png</image>",
            "  </game>",
            "  <folder><path>./Sub</path><name>Sub</name></folder>",
            "  <game><path>./A.gba</path><desc>Mine</desc></game>",
            "</gameList>"
        )
        val m = FrontendMetadata.esdeMerge(existing, mapOf("B.gba" to GameMeta(description = "B desc")))
        assertEquals(
            existing.replace("    <image>./media/B.png</image>\n", "    <image>./media/B.png</image>\n    <desc>B desc</desc>\n"),
            m.content
        )
    }

    @Test fun `esde reads a path inside an unknown tag as no path`() {
        val existing = text("<gameList>", "<game><wrapper><path>./A.gba</path></wrapper><name>x</name></game>", "</gameList>")
        val m = FrontendMetadata.esdeMerge(existing, mapOf("A.gba" to zelda))
        assertEquals(1, m.added)
    }

    @Test fun `esde ignores game tags inside comments and CDATA`() {
        val existing = text(
            "<gameList>",
            "<!-- <game><path>./A.gba</path></game> -->",
            "<game><path>./B.gba</path><desc><![CDATA[Has <game><path>./A.gba</path></game> in it & more]]></desc></game>",
            "</gameList>"
        )
        val m = FrontendMetadata.esdeMerge(existing, mapOf("A.gba" to zelda, "B.gba" to zelda))
        assertEquals(1, m.added)
        val out = m.content!!
        assertTrue(out.contains("<![CDATA[Has <game><path>./A.gba</path></game> in it & more]]>"))
        // B has a description in CDATA: never replaced, so "A hero." is only in the new entry for A.
        assertEquals(1, Regex("A hero\\.").findAll(out).count())
    }

    @Test fun `esde counts a CDATA that holds only blanks as empty`() {
        val existing = text("<gameList>", "<game><path>./A.gba</path><desc><![CDATA[   ]]></desc></game>", "</gameList>")
        val out = FrontendMetadata.esdeMerge(existing, mapOf("A.gba" to GameMeta(description = "Filled"))).content!!
        assertTrue(out.contains("<desc>Filled</desc>"))
        assertFalse(out.contains("CDATA"))
    }

    @Test fun `esde matches paths through entities, dots, slashes and case`() {
        val existing = text(
            "<gameList>",
            "<game><path>./Sonic &amp; Knuckles.md</path></game>",
            "<game><path>  ./Sub/Game.gba </path></game>",
            "<game><path>./CAPS.GBA</path></game>",
            "</gameList>"
        )
        val items = mapOf(
            "Sonic & Knuckles.md" to GameMeta(description = "one"),
            "Sub\\game.gba" to GameMeta(description = "two"),
            "caps.gba" to GameMeta(description = "three")
        )
        val m = FrontendMetadata.esdeMerge(existing, items)
        assertEquals(0, m.added)
        assertEquals(3, m.filled)
    }

    @Test fun `esde matches numeric entities and an absolute path by its end`() {
        val existing = text(
            "<gameList>",
            "<game><path>./Caf&#233;.gba</path></game>",
            "<game><path>/storage/emulated/0/ROMs/gba/Other.gba</path></game>",
            "</gameList>"
        )
        val m = FrontendMetadata.esdeMerge(existing, mapOf("Café.gba" to GameMeta(description = "a"), "Other.gba" to GameMeta(description = "b")))
        assertEquals(0, m.added)
        assertEquals(2, m.filled)
    }

    @Test fun `esde escapes special characters in new entries`() {
        val meta = GameMeta(name = "Q*bert & <Friends>", description = "He said \"hi\" & left <b>fast</b>.\nSecond line.", genre = "Puzzle, \"Arcade\"")
        val out = FrontendMetadata.esdeMerge(null, mapOf("Q&bert's <1>.gba" to meta)).content!!
        assertTrue(out.contains("<path>./Q&amp;bert's &lt;1&gt;.gba</path>"))
        assertTrue(out.contains("<name>Q*bert &amp; &lt;Friends&gt;</name>"))
        assertTrue(out.contains("<desc>He said &quot;hi&quot; &amp; left &lt;b&gt;fast&lt;/b&gt;.\nSecond line.</desc>"))
        assertTrue(out.contains("<genre>Puzzle, &quot;Arcade&quot;</genre>"))
        // And what we wrote is read back as the same game.
        val again = FrontendMetadata.esdeMerge(out, mapOf("Q&bert's <1>.gba" to meta))
        assertNull(again.content)
        assertEquals(1, again.unchanged)
    }

    @Test fun `esde strips characters XML cannot hold and keeps unicode`() {
        val meta = GameMeta(description = "Pokémon ドラゴン 🐉\u0001\u0008 done\r\nnext")
        val out = FrontendMetadata.esdeMerge(null, mapOf("A.gba" to meta)).content!!
        assertTrue(out.contains("<desc>Pokémon ドラゴン 🐉 done\nnext</desc>"))
        assertFalse(out.contains('\u0001'))
        assertFalse(out.contains('\r'))
    }

    @Test fun `esde fills the first of duplicate entries and treats them as one`() {
        val existing = text(
            "<gameList>",
            "\t<game><path>./A.gba</path><name>A</name></game>",
            "\t<game><path>./A.gba</path><desc>From the second</desc></game>",
            "</gameList>"
        )
        val m = FrontendMetadata.esdeMerge(existing, mapOf("A.gba" to zelda))
        assertEquals(1, m.filled)
        assertEquals(0, m.added)
        val out = m.content!!
        // The description exists in the second: not written again; the genre goes into the first.
        assertEquals(1, Regex("<desc>").findAll(out).count())
        assertTrue(out.indexOf("<genre>Adventure</genre>") < out.indexOf("From the second"))
        assertEquals(2, Regex("<game>").findAll(out).count())
    }

    @Test fun `esde appends new entries with the file's own indentation before the closing tag`() {
        val existing = text("<gameList>", "  <game>", "      <path>./A.gba</path>", "  </game>", "</gameList>")
        val out = FrontendMetadata.esdeMerge(existing, mapOf("B.gba" to GameMeta(description = "b"))).content!!
        assertEquals(
            text("<gameList>", "  <game>", "      <path>./A.gba</path>", "  </game>", "  <game>", "      <path>./B.gba</path>", "      <desc>b</desc>", "  </game>", "</gameList>"),
            out
        )
    }

    @Test fun `esde appends into a gamelist without a trailing newline and into an empty or self-closing one`() {
        val compact = "<gameList><game><path>./A.gba</path></game></gameList>"
        val a = FrontendMetadata.esdeMerge(compact, mapOf("B.gba" to GameMeta(description = "b"))).content!!
        assertTrue(a.endsWith("</game>\n</gameList>"))
        assertTrue(a.startsWith("<gameList><game><path>./A.gba</path></game>\n"))
        val b = FrontendMetadata.esdeMerge("<gameList/>", mapOf("B.gba" to GameMeta(description = "b"))).content!!
        assertEquals("<gameList>\n\t<game>\n\t\t<path>./B.gba</path>\n\t\t<desc>b</desc>\n\t</game>\n</gameList>", b)
        val c = FrontendMetadata.esdeMerge("<gameList></gameList>", mapOf("B.gba" to GameMeta(description = "b"))).content!!
        assertEquals("<gameList>\n\t<game>\n\t\t<path>./B.gba</path>\n\t\t<desc>b</desc>\n\t</game>\n</gameList>", c)
    }

    @Test fun `esde keeps the declaration, byte order mark and doctype`() {
        val existing = "﻿<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE gameList [ <!ENTITY x \"a>b\"> ]>\n<gameList>\n<game><path>./A.gba</path></game>\n</gameList>\n"
        val out = FrontendMetadata.esdeMerge(existing, mapOf("A.gba" to GameMeta(description = "d"))).content!!
        assertTrue(out.startsWith("﻿<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE gameList [ <!ENTITY x \"a>b\"> ]>\n<gameList>\n"))
        assertTrue(out.contains("<desc>d</desc>"))
    }

    @Test fun `esde leaves a file it cannot merge alone`() {
        val items = mapOf("A.gba" to zelda)
        assertTrue(FrontendMetadata.esdeMerge("<systemList><system/></systemList>", items).unreadable)
        assertTrue(FrontendMetadata.esdeMerge("<gameList><game><path>./A.gba</path></gameList>", items).unreadable)
        assertTrue(FrontendMetadata.esdeMerge("<gameList>", items).unreadable)
        assertTrue(FrontendMetadata.esdeMerge("not xml at all", items).unreadable)
        assertTrue(FrontendMetadata.esdeMerge("<gameList></gameList><gameList></gameList>", items).unreadable)
        assertTrue(FrontendMetadata.esdeMerge("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n<gameList></gameList>", items).unreadable)
        assertNull(FrontendMetadata.esdeMerge("<gameList><game", items).content)
        assertNull(FrontendMetadata.esdeMissing("<gameList><game", listOf("A.gba")))
    }

    @Test fun `esde accepts attributes with a greater-than sign`() {
        val existing = "<gameList><game note=\"a>b\"><path>./A.gba</path></game></gameList>"
        val m = FrontendMetadata.esdeMerge(existing, mapOf("A.gba" to GameMeta(description = "d")))
        assertEquals("<gameList><game note=\"a>b\"><path>./A.gba</path>\n\t<desc>d</desc></game></gameList>", m.content)
    }

    @Test fun `esde entries without a path are left alone`() {
        val existing = text("<gameList>", "<game><name>No path</name></game>", "</gameList>")
        val m = FrontendMetadata.esdeMerge(existing, mapOf("A.gba" to GameMeta(description = "d")))
        assertEquals(1, m.added)
        assertTrue(m.content!!.contains("<game><name>No path</name></game>"))
    }

    @Test fun `esde fills a name that is missing but never replaces one`() {
        val existing = text("<gameList>", "<game><path>./A.gba</path></game>", "<game><path>./B.gba</path><name>Mine</name></game>", "</gameList>")
        val out = FrontendMetadata.esdeMerge(existing, mapOf("A.gba" to GameMeta(name = "Title A"), "B.gba" to GameMeta(name = "Title B"))).content!!
        assertTrue(out.contains("<name>Title A</name>"))
        assertTrue(out.contains("<name>Mine</name>"))
        assertFalse(out.contains("Title B"))
    }

    @Test fun `esde reports what is missing per game`() {
        val existing = text("<gameList>", "<game><path>./A.gba</path><desc>d</desc><genre>g</genre><name>A</name></game>", "</gameList>")
        val missing = FrontendMetadata.esdeMissing(existing, listOf("A.gba", "B.gba"))!!
        assertEquals(setOf(Field.RELEASE, Field.DEVELOPER, Field.PUBLISHER, Field.RATING), missing.getValue("A.gba"))
        assertEquals(Field.entries.toSet(), missing.getValue("B.gba"))
        assertEquals(Field.entries.toSet(), FrontendMetadata.esdeMissing(null, listOf("A.gba"))!!.getValue("A.gba"))
    }

    @Test fun `esde keeps the order of new entries and counts duplicates in the input once`() {
        val items = linkedMapOf("B.gba" to GameMeta(description = "b"), "A.gba" to GameMeta(description = "a"), "a.GBA" to GameMeta(description = "again"))
        val out = FrontendMetadata.esdeMerge(null, items).content!!
        assertTrue(out.indexOf("./B.gba") < out.indexOf("./A.gba"))
        assertEquals(2, Regex("<game>").findAll(out).count())
        assertFalse(out.contains("again"))
    }

    @Test fun `two names for one existing game are filled once`() {
        val existing = text("<gameList>", "<game><path>./A.gba</path></game>", "</gameList>")
        val m = FrontendMetadata.esdeMerge(existing, linkedMapOf("A.gba" to GameMeta(description = "one"), "a.GBA" to GameMeta(description = "two")))
        assertEquals(1, m.filled)
        assertEquals(1, Regex("<desc>").findAll(m.content!!).count())
        assertTrue(m.content!!.contains("one"))
        val p = FrontendMetadata.pegasusMerge("game: A\nfile: A.gba\n", linkedMapOf("A.gba" to GameMeta(genre = "one"), "a.GBA" to GameMeta(genre = "two")))
        assertEquals("game: A\nfile: A.gba\ngenre: one\n", p.content)
    }

    @Test fun `esde does not touch a game that has everything`() {
        val full = text(
            "<gameList>", "<game><path>./A.gba</path><name>A</name><desc>d</desc><genre>g</genre><releasedate>20000101T000000</releasedate>",
            "<developer>x</developer><publisher>y</publisher><rating>0.5</rating></game>", "</gameList>"
        )
        val m = FrontendMetadata.esdeMerge(full, mapOf("A.gba" to zelda))
        assertNull(m.content)
        assertEquals(1, m.unchanged)
    }

    @Test fun `esde keeps CRLF files readable`() {
        val existing = "<gameList>\r\n\t<game>\r\n\t\t<path>./A.gba</path>\r\n\t</game>\r\n</gameList>\r\n"
        val out = FrontendMetadata.esdeMerge(existing, mapOf("A.gba" to GameMeta(description = "d"))).content!!
        assertTrue(out.contains("<path>./A.gba</path>\n\t\t<desc>d</desc>\r\n\t</game>\r\n</gameList>\r\n"))
    }

    // ---- Pegasus -------------------------------------------------------------------------------

    private val pegasusFile = text(
        "collection: Game Boy Advance", "shortname: gba", "extension: gba", "", "# my favourites", "game: Zelda", "file: Zelda.gba",
        "developer: Capcom", "", "# next", "game: Metroid", "file: Metroid.gba"
    )

    @Test fun `pegasus creates a metadata file when there is none`() {
        val m = FrontendMetadata.pegasusMerge(null, mapOf("Zelda.gba" to zelda))
        assertEquals(1, m.added)
        assertEquals(
            text(
                "# Game information added by Dogmatix+. Entries you wrote yourself are never overwritten.", "",
                "game: The Legend of Zelda", "file: Zelda.gba", "developer: Nintendo", "publisher: Nintendo", "genre: Adventure",
                "release: 1992", "rating: 85%", "description: A hero."
            ),
            m.content
        )
    }

    @Test fun `pegasus fills what is missing after the last line of the entry and keeps the rest`() {
        val m = FrontendMetadata.pegasusMerge(pegasusFile, mapOf("Zelda.gba" to zelda))
        assertEquals(1, m.filled)
        assertEquals(
            text(
                "collection: Game Boy Advance", "shortname: gba", "extension: gba", "", "# my favourites", "game: Zelda", "file: Zelda.gba",
                "developer: Capcom", "description: A hero.", "genre: Adventure", "release: 1992", "publisher: Nintendo", "rating: 85%",
                "", "# next", "game: Metroid", "file: Metroid.gba"
            ),
            m.content
        )
    }

    @Test fun `pegasus adds to the last entry and to new entries at the end of the file`() {
        val m = FrontendMetadata.pegasusMerge(pegasusFile, mapOf("Metroid.gba" to GameMeta(genre = "Action"), "Kirby.gba" to GameMeta(genre = "Platform")))
        assertEquals(
            text(
                "collection: Game Boy Advance", "shortname: gba", "extension: gba", "", "# my favourites", "game: Zelda", "file: Zelda.gba",
                "developer: Capcom", "", "# next", "game: Metroid", "file: Metroid.gba", "genre: Action", "",
                "game: Kirby", "file: Kirby.gba", "genre: Platform"
            ),
            m.content
        )
        assertEquals(1, m.added)
        assertEquals(1, m.filled)
    }

    @Test fun `pegasus never overwrites and replaces an empty key where it stands`() {
        val existing = text("game: A", "file: A.gba", "genre:", "description: Mine", "release: 1999", "x-custom: 1")
        val out = FrontendMetadata.pegasusMerge(existing, mapOf("A.gba" to zelda)).content!!
        assertEquals(
            text("game: A", "file: A.gba", "genre: Adventure", "description: Mine", "release: 1999", "x-custom: 1", "developer: Nintendo", "publisher: Nintendo", "rating: 85%"),
            out
        )
    }

    @Test fun `pegasus sees multi-line values, plural keys and file lists`() {
        val existing = text(
            "game: A", "files:", "  A (Disc 1).cue", "  A (Disc 2).cue", "genres:", "  Action", "  RPG",
            "description:", "  A long text", "  .", "  second paragraph", "developers: Someone"
        )
        val missing = FrontendMetadata.pegasusMissing(existing, listOf("a (disc 2).cue", "Other.gba"))!!
        assertEquals(setOf(Field.RELEASE, Field.PUBLISHER, Field.RATING), missing.getValue("a (disc 2).cue"))
        assertEquals(Field.entries.size - 1, missing.getValue("Other.gba").size)
        val m = FrontendMetadata.pegasusMerge(existing, mapOf("A (Disc 1).cue" to zelda))
        assertEquals(1, m.filled)
        assertEquals(3, m.fields)
        assertFalse(m.content!!.contains("A hero."))
    }

    @Test fun `pegasus writes long descriptions as paragraphs with a dot line between them`() {
        val meta = GameMeta(description = "First paragraph\nwraps here.\n\n\n\nSecond paragraph.\n\nThird.")
        val out = FrontendMetadata.pegasusMerge(null, mapOf("A.gba" to meta)).content!!
        assertTrue(out.contains("description: First paragraph wraps here.\n  .\n  Second paragraph.\n  .\n  Third.\n"))
    }

    @Test fun `pegasus does not read indented text or comments as keys`() {
        val existing = text("game: A", "file: A.gba", "description: Intro", "  Note: this is text", "  # not a comment here", "# a real comment", "game: B", "file: B.gba")
        val doc = FrontendMetadata.pegasusMissing(existing, listOf("A.gba", "B.gba"))!!
        assertFalse(Field.DESCRIPTION in doc.getValue("A.gba"))
        assertTrue(Field.DESCRIPTION in doc.getValue("B.gba"))
        val out = FrontendMetadata.pegasusMerge(existing, mapOf("A.gba" to GameMeta(genre = "G"))).content!!
        assertEquals(
            text("game: A", "file: A.gba", "description: Intro", "  Note: this is text", "  # not a comment here", "genre: G", "# a real comment", "game: B", "file: B.gba"),
            out
        )
    }

    @Test fun `pegasus keeps unusual values whole`() {
        val meta = GameMeta(name = "Rock: The Game #1", description = "Ratio 3:2, see http://x.y/z # tag", developer = "A & B <C>", genre = "Action: Run")
        val out = FrontendMetadata.pegasusMerge(null, mapOf("Rock: The Game #1.gba" to meta)).content!!
        assertTrue(out.contains("game: Rock: The Game #1\nfile: Rock: The Game #1.gba\n"))
        assertTrue(out.contains("developer: A & B <C>\n"))
        assertTrue(out.contains("genre: Action: Run\n"))
        assertTrue(out.contains("description: Ratio 3:2, see http://x.y/z # tag\n"))
    }

    @Test fun `pegasus makes values single lines and drops control characters`() {
        val out = FrontendMetadata.pegasusMerge(null, mapOf("A.gba" to GameMeta(name = "Two\nLines", genre = "A,\n B\u0001", developer = " Dev \t Co "))).content!!
        assertTrue(out.contains("game: Two Lines\n"))
        assertTrue(out.contains("genre: A, B\n"))
        assertTrue(out.contains("developer: Dev Co\n"))
    }

    @Test fun `pegasus keeps CRLF, a byte order mark and a missing final newline`() {
        val existing = "﻿game: A\r\nfile: A.gba"
        val out = FrontendMetadata.pegasusMerge(existing, mapOf("A.gba" to GameMeta(genre = "G"), "B.gba" to GameMeta(genre = "H"))).content!!
        assertEquals("﻿game: A\r\nfile: A.gba\r\ngenre: G\r\n\r\ngame: B\r\nfile: B.gba\r\ngenre: H\r\n", out)
    }

    @Test fun `pegasus treats duplicates as one entry`() {
        val existing = text("game: A", "file: A.gba", "", "game: A again", "file: A.gba", "genre: Mine")
        val m = FrontendMetadata.pegasusMerge(existing, mapOf("A.gba" to GameMeta(genre = "G", developer = "D")))
        assertEquals(1, m.filled)
        assertEquals(1, m.fields)
        assertEquals(text("game: A", "file: A.gba", "developer: D", "", "game: A again", "file: A.gba", "genre: Mine"), m.content)
    }

    @Test fun `pegasus matches files by relative path and case`() {
        val existing = text("game: A", "file: ./Sub/A.gba")
        val m = FrontendMetadata.pegasusMerge(existing, mapOf("sub\\a.GBA" to GameMeta(genre = "G")))
        assertEquals(0, m.added)
        assertEquals(1, m.filled)
    }

    @Test fun `pegasus is idempotent and leaves binary files alone`() {
        val once = FrontendMetadata.pegasusMerge(pegasusFile, mapOf("Zelda.gba" to zelda)).content!!
        val twice = FrontendMetadata.pegasusMerge(once, mapOf("Zelda.gba" to zelda))
        assertNull(twice.content)
        assertEquals(1, twice.unchanged)
        assertTrue(FrontendMetadata.pegasusMerge("game: A\u0000\u0000binary", mapOf("A.gba" to zelda)).unreadable)
    }

    @Test fun `pegasus makes no entry for a game without information`() {
        val m = FrontendMetadata.pegasusMerge(pegasusFile, mapOf("New.gba" to GameMeta(name = "New")))
        assertNull(m.content)
        assertEquals(1, m.skipped)
    }

    @Test fun `pegasus keeps lines it does not understand`() {
        val existing = text("collection: X", "launch: am start {file.path}", "random text without key", "", "game: A", "file: A.gba", "x-custom.key: 5")
        val out = FrontendMetadata.pegasusMerge(existing, mapOf("A.gba" to GameMeta(genre = "G"))).content!!
        assertEquals(text("collection: X", "launch: am start {file.path}", "random text without key", "", "game: A", "file: A.gba", "x-custom.key: 5", "genre: G"), out)
    }

    // ---- Small helpers ---------------------------------------------------------------------------

    @Test fun `dates are normalised`() {
        assertEquals("2004", FrontendMetadata.isoDate("2004"))
        assertEquals("2004-11", FrontendMetadata.isoDate("2004-11"))
        assertEquals("2004-11-21", FrontendMetadata.isoDate("2004-11-21"))
        assertEquals("2004-01-05", FrontendMetadata.isoDate("2004/1/5"))
        assertEquals("2004", FrontendMetadata.isoDate("2004-13-40"))
        assertNull(FrontendMetadata.isoDate(""))
        assertNull(FrontendMetadata.isoDate("unknown"))
    }

    @Test fun `a long description is cut at a word`() {
        val text = "word ".repeat(2000)
        val shown = GameMeta(description = text).text(Field.DESCRIPTION)!!
        assertTrue(shown.length <= 6001)
        assertTrue(shown.endsWith("word…"))
    }

    @Test fun `a game only counts as informative with more than a name`() {
        assertFalse(GameMeta(name = "A").hasData)
        assertFalse(GameMeta(ratingPercent = 0).hasData)
        assertTrue(GameMeta(ratingPercent = 50).hasData)
        assertTrue(GameMeta(genre = "x").hasData)
    }

    @Test fun `blank fields are taken from the next source`() {
        val a = GameMeta(description = "from a")
        val b = GameMeta(description = "from b", genre = "G", ratingPercent = 70)
        val merged = a.orElse(b)
        assertEquals("from a", merged.description)
        assertEquals("G", merged.genre)
        assertEquals(70, merged.ratingPercent)
    }

    @Test fun `the console folder is found like the cover code does`() {
        val file = diskFile("a.gba", folder = "/storage/ROMs/gba/USA/More", consoleId = "gba")
        assertEquals("gba", CoverPlanner.systemOf(file))
        val loc = FrontendMetadata.locate("gba", file.folder)!!
        assertEquals("gba", loc.system)
        assertEquals("USA/More", loc.subPath)
        assertEquals("USA/More/a.gba", loc.relative("a.gba"))
        assertEquals("a.gba", FrontendMetadata.locate("gba", "/storage/ROMs/gba")!!.relative("a.gba"))
        assertNull(FrontendMetadata.locate("gba", "/storage/Stuff"))
        assertNull(CoverPlanner.systemOf(diskFile("a.gba", folder = "/storage/Stuff", consoleId = "gba")))
    }

    @Test fun `the launch file of a disc image is its sheet or playlist`() {
        assertEquals("Game.cue", FrontendMetadata.mainFile(listOf("Game.bin", "Game.cue", "Game (Track 2).wav")))
        assertEquals("Game.m3u", FrontendMetadata.mainFile(listOf("Game (Disc 1).cue", "Game (Disc 1).bin", "Game.m3u")))
        assertEquals("disc.gdi", FrontendMetadata.mainFile(listOf("track01.bin", "disc.gdi", "track02.raw")))
        assertEquals("Sonic.bin", FrontendMetadata.mainFile(listOf("Sonic.bin")))
        assertEquals("Game.gba", FrontendMetadata.mainFile(listOf("Game.gba")))
        assertNull(FrontendMetadata.mainFile(emptyList()))
    }

    @Test fun `only valid UTF-8 is read`() {
        assertEquals("café", FrontendMetadata.decodeUtf8Strict("café".toByteArray(Charsets.UTF_8)))
        assertNull(FrontendMetadata.decodeUtf8Strict(byteArrayOf(0x63, 0xE9.toByte(), 0x20)))
    }

    @Test fun `the backup sits next to the file under its own name`() {
        assertEquals("gamelist.xml.dogmatix-bak", FrontendMetadata.backupName(FrontendMetadata.ESDE_FILE))
        assertEquals("metadata.txt.dogmatix-bak", FrontendMetadata.backupName(FrontendMetadata.PEGASUS_FILE))
        assertEquals("gamelists/gba", FrontendMetadata.esdeGamelistDir("gba"))
    }

    @Test fun `a merge result carries its counts`() {
        val m = FrontendMetadata.esdeMerge(played, mapOf("Zelda.gba" to zelda, "New.gba" to zelda, "Nothing.gba" to GameMeta(name = "x")))
        assertEquals(1, m.added)
        assertEquals(1, m.filled)
        assertEquals(1, m.skipped)
        assertNotNull(m.content)
    }

    @Test fun `esde queues a field once when two items resolve to one entry`() {
        val existing = text("<gameList>", "<game><path>/home/roms/gba/A.gba</path><desc></desc></game>", "</gameList>")
        val m = FrontendMetadata.esdeMerge(existing, linkedMapOf("A.gba" to GameMeta(description = "one", genre = "g1"), "gba/A.gba" to GameMeta(description = "two", genre = "g2")))
        val out = m.content!!
        assertEquals(1, Regex("<desc>").findAll(out).count())
        assertEquals(1, Regex("<genre>").findAll(out).count())
        assertTrue(out.contains("one") && out.contains("g1") && !out.contains("two") && !out.contains("g2"))
    }

    @Test fun `pegasus queues a field once when two items map to one entry`() {
        val existing = text("game: Disc", "files:", "  Disc1.bin", "  Disc2.bin")
        val m = FrontendMetadata.pegasusMerge(existing, linkedMapOf("Disc1.bin" to GameMeta(genre = "one"), "Disc2.bin" to GameMeta(genre = "two")))
        assertEquals(1, Regex("genre:").findAll(m.content!!).count())
        assertTrue(m.content!!.contains("one") && !m.content!!.contains("two"))
    }
}
