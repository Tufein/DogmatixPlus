package com.cortinadev.dogmatix.util

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * Game information (name, description, genre, release, developer, publisher, rating) for the
 * frontends the app already writes covers for (7.0): ES-DE's `gamelist.xml` and Pegasus'
 * `metadata.txt`. Pure JVM so the careful part, merging into a file the user (or a scraper)
 * already filled, is unit-tested.
 *
 * The rules of both merges:
 *  - a field is only filled when it is empty: whatever the file already says stays as it is;
 *  - nothing is removed, reordered or rewritten: the changes are insertions into the original
 *    text, so playcount, lastplayed, favourite, images, unknown tags, comments, CDATA and the
 *    user's formatting survive byte for byte;
 *  - a file that cannot be read with certainty (not a gamelist, not UTF-8, binary) is left
 *    alone: [Merge.unreadable];
 *  - a game that is listed twice counts as one (the frontends merge such entries too): a field
 *    is empty only when it is empty in all of them, and it is filled in the first;
 *  - a game without an entry gets one only when there is something to say about it.
 */
object FrontendMetadata {

    /** What can be written. [NAME] is only ever filled in; it is never a reason to look a game up. */
    enum class Field { NAME, DESCRIPTION, GENRE, RELEASE, DEVELOPER, PUBLISHER, RATING }

    /** Everything known about one game; blank / null = unknown. */
    data class GameMeta(
        val name: String = "",
        val description: String = "",
        val genre: String = "",
        /** A year or an ISO date: `2004`, `2004-11`, `2004-11-21`. */
        val released: String = "",
        val developer: String = "",
        val publisher: String = "",
        /** 1-100; null = not rated. */
        val ratingPercent: Int? = null
    ) {
        /** Something besides a name: enough to be worth an entry of its own. */
        val hasData: Boolean get() = Field.entries.any { it != Field.NAME && text(it) != null }

        /** [field] as it will be written (cleaned up); null when there is nothing to write. */
        fun text(field: Field): String? = when (field) {
            Field.NAME -> oneLine(name)
            Field.DESCRIPTION -> paragraphs(description)
            Field.GENRE -> oneLine(genre)
            Field.RELEASE -> isoDate(released)
            Field.DEVELOPER -> oneLine(developer)
            Field.PUBLISHER -> oneLine(publisher)
            Field.RATING -> ratingPercent?.takeIf { it in 1..100 }?.toString()
        }

        /** This, with every blank field taken from [other]. */
        fun orElse(other: GameMeta): GameMeta = GameMeta(
            name = name.ifBlank { other.name },
            description = description.ifBlank { other.description },
            genre = genre.ifBlank { other.genre },
            released = released.ifBlank { other.released },
            developer = developer.ifBlank { other.developer },
            publisher = publisher.ifBlank { other.publisher },
            ratingPercent = ratingPercent ?: other.ratingPercent
        )
    }

    /**
     * What a merge did. [content] is the file's new text, or null when there is nothing to
     * write (nothing to add, or the file cannot be merged: see [unreadable]).
     */
    data class Merge(
        val content: String?,
        /** New entries (games the file did not list). */
        val added: Int = 0,
        /** Existing entries that got at least one empty field filled. */
        val filled: Int = 0,
        /** Fields written into existing entries. */
        val fields: Int = 0,
        /** Existing entries that needed nothing. */
        val unchanged: Int = 0,
        /** Games without an entry and without anything to say: no entry was made. */
        val skipped: Int = 0,
        /** The file exists but cannot be merged with certainty; it must not be touched. */
        val unreadable: Boolean = false
    )

    // ---- Where things live ----------------------------------------------------------------

    const val ESDE_FILE = "gamelist.xml"
    const val PEGASUS_FILE = "metadata.txt"
    /** Pegasus reads this name as well; it is only used when a file of this name already exists and `metadata.txt` does not. */
    const val PEGASUS_ALT_FILE = "metadata.pegasus.txt"
    const val BACKUP_SUFFIX = ".dogmatix-bak"

    /** The one backup copy kept next to a file the first time it is changed. */
    fun backupName(fileName: String): String = fileName + BACKUP_SUFFIX

    /** Folder (below ES-DE's data folder) of a system's gamelist. */
    fun esdeGamelistDir(system: String): String = "gamelists/$system"

    /** Where a game file sits relative to its ES-DE system: the system folder's name and any sub-folders below it. */
    data class Location(val system: String, val subPath: String) {
        /** The path of [fileName] as ES-DE lists it, relative to the system folder (`Sub/Game.gba`). */
        fun relative(fileName: String): String = if (subPath.isEmpty()) fileName else "$subPath/$fileName"
    }

    /**
     * The console folder in [folder] (the readable path of the folder holding the file): the first
     * segment that names [consoleId], as the cover code finds it ([CoverPlanner.systemOf]); null when
     * no segment does.
     */
    fun locate(consoleId: String, folder: String): Location? {
        val parts = folder.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() }
        val i = parts.indexOfFirst { ConsoleFolderAliases.matches(consoleId, it) }
        if (i < 0) return null
        return Location(parts[i], parts.drop(i + 1).joinToString("/"))
    }

    /** Extensions of the file that launches a disc image, best first. */
    private val launchOrder = listOf("m3u", "cue", "gdi", "ccd", "mds", "toc", "chd", "iso", "pbp", "cso", "rvz", "wbfs", "gcz", "zip", "7z")
    private val audioParts = setOf("wav", "mp3", "ogg", "flac", "ape")

    /**
     * The file a frontend lists for a game made of [names] (`Game.cue` + `Game.bin` + tracks → the
     * sheet, a playlist before everything else); null when there are none.
     */
    fun mainFile(names: Collection<String>): String? {
        if (names.isEmpty()) return null
        fun ext(n: String) = n.substringAfterLast('.', "").lowercase()
        val sorted = names.sorted()
        return sorted.minByOrNull { n ->
            val rank = launchOrder.indexOf(ext(n))
            if (rank >= 0) rank else if (ext(n) in audioParts) launchOrder.size + 1 else launchOrder.size
        }
    }

    // ---- Cleaning ----------------------------------------------------------------------------

    /** Text safe for XML 1.0 and for one line of a file: no control characters, no lone surrogates, `\n` line ends. */
    internal fun clean(s: String): String {
        val text = s.replace("\r\n", "\n").replace('\r', '\n')
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\n' || c == '\t' || (c >= ' ' && c != '￾' && c != '￿' && !c.isSurrogate()) -> out.append(c)
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> { out.append(c).append(text[i + 1]); i++ }
            }
            i++
        }
        return out.toString()
    }

    private val whitespace = Regex("\\s+")

    private fun oneLine(s: String): String? = clean(s).replace(whitespace, " ").trim().takeIf { it.isNotEmpty() }

    private const val MAX_DESCRIPTION = 6000

    /** A description with trimmed lines and at most one blank line in a row; null when empty. */
    private fun paragraphs(s: String): String? {
        val text = clean(s).lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()
        if (text.isEmpty()) return null
        if (text.length <= MAX_DESCRIPTION) return text
        val cut = text.substring(0, MAX_DESCRIPTION)
        return (cut.substringBeforeLast(' ', cut).ifBlank { cut }).trimEnd() + "…"
    }

    private val dateRegex = Regex("""^\s*(\d{4})(?:[-/.](\d{1,2}))?(?:[-/.](\d{1,2}))?""")

    /** `2004`, `2004-11` or `2004-11-21` from what a database gave; null when it is not a date. */
    fun isoDate(s: String): String? {
        val m = dateRegex.find(s) ?: return null
        val year = m.groupValues[1].toInt()
        if (year !in 1900..2100) return null
        val month = m.groupValues[2].toIntOrNull()?.takeIf { it in 1..12 } ?: return year.toString()
        val day = m.groupValues[3].toIntOrNull()?.takeIf { it in 1..31 } ?: return "%04d-%02d".format(Locale.ROOT, year, month)
        return "%04d-%02d-%02d".format(Locale.ROOT, year, month, day)
    }

    /** ES-DE's date format: `2004-11-21` → `20041121T000000` (a bare year is January 1st). */
    fun esdeDate(iso: String): String? {
        val d = isoDate(iso) ?: return null
        val p = d.split('-')
        return "%s%s%sT000000".format(Locale.ROOT, p[0], p.getOrElse(1) { "01" }, p.getOrElse(2) { "01" })
    }

    /** ES-DE's rating is 0-1: 85 → `0.85`, 100 → `1`. */
    fun esdeRating(percent: Int): String {
        val v = percent.coerceIn(1, 100) / 100.0
        return "%.2f".format(Locale.ROOT, v).trimEnd('0').trimEnd('.')
    }

    /** Escapes text for an XML element. */
    fun xmlEscape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /**
     * [bytes] as UTF-8 text (a leading byte-order mark is kept); null when they are not valid UTF-8,
     * because writing such a file back would damage it.
     */
    fun decodeUtf8Strict(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    /** Normalised path used to match a game: `./Sub\Game.gba` and `sub/game.gba` are the same file. */
    internal fun pathKey(path: String): String = path.trim().replace('\\', '/').removePrefix("./").lowercase()

    private fun isAbsolute(key: String): Boolean = key.startsWith("/") || key.startsWith("~") || Regex("^[a-z]:/").containsMatchIn(key)

    private fun <T> findEntries(byKey: Map<String, T>, key: String): T? =
        byKey[key] ?: byKey.entries.firstOrNull { (k, _) -> isAbsolute(k) && k.endsWith("/$key") }?.value

    // =============================================================================================
    // ES-DE gamelist.xml
    // =============================================================================================

    /** One element of a parsed XML file, with where it sits in the text (so merges are insertions). */
    private class XEl(val name: String, val start: Int, val openEnd: Int, selfClosing: Boolean) {
        /** Offset of `</name>`; for a self-closing element the end of the tag. */
        var closeStart = openEnd
        /** Offset after the closing `>`. */
        var end = openEnd
        val selfClosing = selfClosing
        val children = ArrayList<XEl>()
    }

    private fun isBlankRange(t: String, from: Int, to: Int): Boolean {
        for (i in from until to) if (!t[i].isWhitespace()) return false
        return true
    }

    /**
     * The root element of [t] with every child's position, or null when it is not well-formed
     * enough to edit with certainty. Comments, CDATA, processing instructions and a DOCTYPE are
     * skipped; attributes may hold `>` inside quotes.
     */
    private fun parseXml(t: String): XEl? {
        var i = if (t.startsWith("﻿")) 1 else 0
        val n = t.length
        val stack = ArrayList<XEl>()
        var root: XEl? = null
        while (i < n) {
            val lt = t.indexOf('<', i)
            if (lt < 0) {
                if (!isBlankRange(t, i, n)) return null
                break
            }
            if (stack.isEmpty() && !isBlankRange(t, i, lt)) return null
            when {
                t.startsWith("<!--", lt) -> { val e = t.indexOf("-->", lt + 4); if (e < 0) return null; i = e + 3 }
                t.startsWith("<![CDATA[", lt) -> {
                    if (stack.isEmpty()) return null
                    val e = t.indexOf("]]>", lt + 9); if (e < 0) return null; i = e + 3
                }
                t.startsWith("<?", lt) -> { val e = t.indexOf("?>", lt + 2); if (e < 0) return null; i = e + 2 }
                t.startsWith("<!", lt) -> {
                    var depth = 0
                    var quote = ' '
                    var k = lt + 2
                    while (k < n) {
                        val c = t[k]
                        if (quote != ' ') { if (c == quote) quote = ' ' }
                        else if (c == '"' || c == '\'') quote = c
                        else if (c == '[') depth++
                        else if (c == ']') depth--
                        else if (c == '>' && depth <= 0) break
                        k++
                    }
                    if (k >= n) return null
                    i = k + 1
                }
                t.startsWith("</", lt) -> {
                    val gt = t.indexOf('>', lt + 2); if (gt < 0) return null
                    val top = stack.lastOrNull() ?: return null
                    if (top.name != t.substring(lt + 2, gt).trim()) return null
                    top.closeStart = lt
                    top.end = gt + 1
                    stack.removeAt(stack.lastIndex)
                    i = gt + 1
                }
                else -> {
                    var j = lt + 1
                    while (j < n && !t[j].isWhitespace() && t[j] != '>' && t[j] != '/') j++
                    if (j == lt + 1) return null
                    var quote = ' '
                    var k = j
                    while (k < n) {
                        val c = t[k]
                        if (quote != ' ') { if (c == quote) quote = ' ' }
                        else if (c == '"' || c == '\'') quote = c
                        else if (c == '>') break
                        k++
                    }
                    if (k >= n) return null
                    val selfClosing = t[k - 1] == '/' && k - 1 >= j
                    val el = XEl(t.substring(lt + 1, j), lt, k + 1, selfClosing)
                    if (stack.isEmpty()) { if (root != null) return null; root = el } else stack.last().children += el
                    if (!selfClosing) stack += el
                    i = k + 1
                }
            }
        }
        return if (stack.isEmpty()) root else null
    }

    /** The text of [el] as an XML reader sees it: entities resolved, CDATA taken literally, comments and tags dropped. */
    private fun innerText(t: String, el: XEl): String {
        if (el.selfClosing) return ""
        val sb = StringBuilder()
        var i = el.openEnd
        val end = el.closeStart
        while (i < end) {
            val c = t[i]
            when {
                t.startsWith("<![CDATA[", i) -> {
                    val e = t.indexOf("]]>", i).takeIf { it in 0 until end } ?: break
                    sb.append(t, i + 9, e); i = e + 3
                }
                t.startsWith("<!--", i) -> { val e = t.indexOf("-->", i).takeIf { it in 0 until end } ?: break; i = e + 3 }
                c == '<' -> { val e = t.indexOf('>', i).takeIf { it in 0 until end } ?: break; i = e + 1 }
                c == '&' -> {
                    val semi = t.indexOf(';', i)
                    val decoded = if (semi in (i + 2)..(i + 10)) entity(t.substring(i + 1, semi)) else null
                    if (decoded != null) { sb.append(decoded); i = semi + 1 } else { sb.append(c); i++ }
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    private fun entity(name: String): String? = when {
        name == "amp" -> "&"
        name == "lt" -> "<"
        name == "gt" -> ">"
        name == "quot" -> "\""
        name == "apos" -> "'"
        name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }
        name.startsWith("#") -> name.substring(1).toIntOrNull()?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }
        else -> null
    }

    private val xmlDeclaration = Regex("""^﻿?\s*<\?xml\s[^>]*?encoding\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    /** A declared encoding other than UTF-8 means the text we decoded is wrong: such a file is not touched. */
    private fun encodingIsUtf8(text: String): Boolean {
        val declared = xmlDeclaration.find(text)?.groupValues?.get(1)?.lowercase() ?: return true
        return declared == "utf-8" || declared == "utf8" || declared == "us-ascii"
    }

    private class EsdeDoc(val text: String, val root: XEl, val byKey: Map<String, List<XEl>>)

    private fun esdeParse(text: String): EsdeDoc? {
        if (!encodingIsUtf8(text)) return null
        val root = parseXml(text) ?: return null
        if (root.name != "gameList") return null
        val byKey = LinkedHashMap<String, MutableList<XEl>>()
        for (game in root.children) {
            if (game.name != "game") continue
            val path = game.children.firstOrNull { it.name == "path" } ?: continue
            val key = pathKey(innerText(text, path))
            if (key.isNotEmpty()) byKey.getOrPut(key) { ArrayList() } += game
        }
        return EsdeDoc(text, root, byKey)
    }

    private fun esdeTag(f: Field): String = when (f) {
        Field.NAME -> "name"
        Field.DESCRIPTION -> "desc"
        Field.GENRE -> "genre"
        Field.RELEASE -> "releasedate"
        Field.DEVELOPER -> "developer"
        Field.PUBLISHER -> "publisher"
        Field.RATING -> "rating"
    }

    /** Whether [value] (an element's text) counts as "not filled in": blank, ES-DE's epoch date, a rating of 0. */
    private fun esdeEmpty(f: Field, value: String): Boolean {
        val v = value.trim()
        return when (f) {
            Field.RELEASE -> v.isEmpty() || v.startsWith("19700101")
            Field.RATING -> v.isEmpty() || (v.toDoubleOrNull()?.let { it <= 0.0 } ?: false)
            else -> v.isEmpty()
        }
    }

    private fun esdeFilled(doc: EsdeDoc, entries: List<XEl>, f: Field): Boolean {
        val tag = esdeTag(f)
        return entries.any { g -> g.children.any { it.name == tag && !esdeEmpty(f, innerText(doc.text, it)) } }
    }

    private fun esdeRender(f: Field, value: String): String? = when (f) {
        Field.RELEASE -> esdeDate(value)
        Field.RATING -> value.toIntOrNull()?.let(::esdeRating)
        else -> xmlEscape(value)
    }

    /** Order of the fields in a new ES-DE entry. */
    private val esdeOrder = listOf(Field.NAME, Field.DESCRIPTION, Field.RATING, Field.RELEASE, Field.DEVELOPER, Field.PUBLISHER, Field.GENRE)

    private fun esdeEntry(file: String, meta: GameMeta, gameIndent: String, childIndent: String): String = buildString {
        append(gameIndent).append("<game>\n")
        append(childIndent).append("<path>./").append(xmlEscape(file.replace('\\', '/').removePrefix("./"))).append("</path>\n")
        for (f in esdeOrder) {
            val value = meta.text(f)?.let { esdeRender(f, it) } ?: continue
            append(childIndent).append('<').append(esdeTag(f)).append('>').append(value).append("</").append(esdeTag(f)).append(">\n")
        }
        append(gameIndent).append("</game>\n")
    }

    /** The blanks in front of [offset] on its line; null when something else is in front of it. */
    private fun lineIndent(t: String, offset: Int): String? {
        var i = offset
        while (i > 0 && (t[i - 1] == ' ' || t[i - 1] == '\t')) i--
        return if (i == 0 || t[i - 1] == '\n') t.substring(i, offset) else null
    }

    /**
     * The fields each of [files] (as the gamelist lists them: `Game.gba`, `Sub/Game.gba`) still lacks in
     * [existing]; a game without an entry lacks all of them. Null when [existing] cannot be merged.
     * The caller looks a game up only when something other than [Field.NAME] is missing.
     */
    fun esdeMissing(existing: String?, files: Collection<String>): Map<String, Set<Field>>? {
        val all = Field.entries.toSet()
        if (existing == null || existing.isBlank()) return files.associateWith { all }
        val doc = esdeParse(existing) ?: return null
        return files.associateWith { file ->
            val entries = findEntries(doc.byKey, pathKey(file)) ?: return@associateWith all
            Field.entries.filter { !esdeFilled(doc, entries, it) }.toSet()
        }
    }

    private class Edit(val start: Int, val end: Int, val text: String, val seq: Int)

    /** [existing] gamelist (null / blank = none yet) with [items] (file name → details) merged in. */
    fun esdeMerge(existing: String?, items: Map<String, GameMeta>): Merge {
        if (existing == null || existing.isBlank()) {
            val fresh = items.entries.filter { it.value.hasData }.distinctBy { pathKey(it.key) }
            if (fresh.isEmpty()) return Merge(null, skipped = items.size)
            val out = StringBuilder("<?xml version=\"1.0\"?>\n<gameList>\n")
            fresh.forEach { out.append(esdeEntry(it.key, it.value, "\t", "\t\t")) }
            out.append("</gameList>\n")
            return Merge(out.toString(), added = fresh.size, skipped = items.size - fresh.size)
        }
        val doc = esdeParse(existing) ?: return Merge(null, unreadable = true)
        val t = doc.text
        val edits = ArrayList<Edit>()
        var added = 0; var filled = 0; var fields = 0; var unchanged = 0; var skipped = 0
        val fresh = LinkedHashMap<String, Pair<String, GameMeta>>()

        val seen = HashSet<String>()
        for ((file, meta) in items) {
            val key = pathKey(file)
            // Two names for one file (case, ./): the first one counts.
            if (!seen.add(key)) continue
            val entries = findEntries(doc.byKey, key)
            if (entries == null) {
                if (meta.hasData) fresh.putIfAbsent(key, file to meta) else skipped++
                continue
            }
            val first = entries.first()
            val gameIndent = lineIndent(t, first.start) ?: ""
            val childIndent = first.children.firstOrNull()?.let { lineIndent(t, it.start) } ?: (gameIndent + "\t")
            val appended = StringBuilder()
            var n = 0
            for (f in Field.entries) {
                val value = meta.text(f)?.let { esdeRender(f, it) } ?: continue
                if (esdeFilled(doc, entries, f)) continue
                val tag = esdeTag(f)
                val element = "<$tag>$value</$tag>"
                val blank = first.children.firstOrNull { it.name == tag }
                if (blank != null) edits += Edit(blank.start, blank.end, element, edits.size)
                else appended.append('\n').append(childIndent).append(element)
                n++
            }
            if (appended.isNotEmpty()) {
                val at = first.children.lastOrNull()?.end ?: first.openEnd
                edits += Edit(at, at, appended.toString(), edits.size)
            }
            if (n > 0) { filled++; fields += n } else unchanged++
        }

        if (fresh.isNotEmpty()) {
            val firstGame = doc.root.children.firstOrNull { it.name == "game" }
            val gameIndent = firstGame?.let { lineIndent(t, it.start) } ?: "\t"
            val childIndent = firstGame?.children?.firstOrNull()?.let { lineIndent(t, it.start) } ?: (gameIndent + "\t")
            val block = fresh.values.joinToString("") { (file, meta) -> esdeEntry(file, meta, gameIndent, childIndent) }
            val root = doc.root
            if (root.selfClosing) {
                edits += Edit(root.start, root.end, "<${root.name}>\n$block</${root.name}>", edits.size)
            } else {
                // Before the closing tag's own line, so the entries sit with their siblings.
                var at = root.closeStart
                while (at > root.openEnd && (t[at - 1] == ' ' || t[at - 1] == '\t')) at--
                val atLineStart = at > 0 && t[at - 1] == '\n'
                if (atLineStart) edits += Edit(at, at, block, edits.size)
                else edits += Edit(root.closeStart, root.closeStart, "\n$block", edits.size)
            }
            added = fresh.size
        }

        if (edits.isEmpty()) return Merge(null, unchanged = unchanged, skipped = skipped)
        val out = StringBuilder(t)
        for (e in edits.sortedWith(compareByDescending<Edit> { it.start }.thenByDescending { it.seq })) out.replace(e.start, e.end, e.text)
        return Merge(out.toString(), added, filled, fields, unchanged, skipped)
    }

    // =============================================================================================
    // Pegasus metadata.txt
    // =============================================================================================

    private class PLine(var text: String, var eol: String)

    /** One `key: value` of a Pegasus file with its continuation lines (the lines after it that start with a blank). */
    private class PKey(val raw: String, val key: String, val line: Int, val value: String) {
        var last = line
        val continuation = ArrayList<String>()
        val isEmpty: Boolean get() = value.isEmpty() && continuation.all { it.isEmpty() || it == "." }
    }

    /** A `game:` or `collection:` block: its first line and the keys in it (the first is the entry's own). */
    private class PEntry(val kind: String) { val keys = ArrayList<PKey>() }

    private class PegasusDoc(val bom: String, val lines: MutableList<PLine>, val eol: String, val byKey: Map<String, List<PEntry>>)

    private fun splitLines(body: String): MutableList<PLine> {
        val out = ArrayList<PLine>()
        var i = 0
        while (i < body.length) {
            val nl = body.indexOf('\n', i)
            if (nl < 0) { out += PLine(body.substring(i), ""); break }
            if (nl > i && body[nl - 1] == '\r') out += PLine(body.substring(i, nl - 1), "\r\n") else out += PLine(body.substring(i, nl), "\n")
            i = nl + 1
        }
        return out
    }

    private fun pegasusParse(text: String): PegasusDoc? {
        if (text.indexOf('\u0000') >= 0) return null
        val bom = if (text.startsWith("﻿")) "﻿" else ""
        val lines = splitLines(text.substring(bom.length))
        val crlf = lines.count { it.eol == "\r\n" }
        val eol = if (crlf > lines.count { it.eol == "\n" }) "\r\n" else "\n"
        // The file always ends with a line end once it is changed, so appended lines never join the last one.
        lines.lastOrNull()?.let { if (it.eol.isEmpty()) it.eol = eol }

        val entries = ArrayList<PEntry>()
        var current: PEntry? = null
        var key: PKey? = null
        for ((i, line) in lines.withIndex()) {
            val s = line.text
            if (s.isBlank() || s[0] == '#') continue
            if (s[0] == ' ' || s[0] == '\t') { key?.let { it.continuation += s.trim(); it.last = i }; continue }
            val colon = s.indexOf(':')
            val name = if (colon > 0) s.substring(0, colon).trim().lowercase() else ""
            if (name.isEmpty() || name.any { it.isWhitespace() }) { key = null; continue }
            val k = PKey(s.substring(0, colon), name, i, s.substring(colon + 1).trim())
            if (name == "game" || name == "collection") { current = PEntry(name).also { entries += it } }
            if (current == null) { key = null; continue }
            current.keys += k
            key = k
        }
        val byKey = LinkedHashMap<String, MutableList<PEntry>>()
        for (e in entries) {
            if (e.kind != "game") continue
            for (k in e.keys) {
                if (k.key != "file" && k.key != "files") continue
                (listOf(k.value) + k.continuation).filter { it.isNotEmpty() && it != "." }.map(::pathKey).distinct()
                    .forEach { path -> byKey.getOrPut(path) { ArrayList() }.let { if (e !in it) it += e } }
            }
        }
        return PegasusDoc(bom, lines, eol, byKey)
    }

    private fun pegasusKeys(f: Field): Set<String> = when (f) {
        Field.NAME -> setOf("game")
        Field.DESCRIPTION -> setOf("description")
        Field.GENRE -> setOf("genre", "genres")
        Field.RELEASE -> setOf("release", "releases")
        Field.DEVELOPER -> setOf("developer", "developers")
        Field.PUBLISHER -> setOf("publisher", "publishers")
        Field.RATING -> setOf("rating")
    }

    /** Pegasus names a game by its `game:` line, so the name is never a field to fill. */
    private val pegasusFields = Field.entries.filter { it != Field.NAME }

    private fun pegasusFilled(entries: List<PEntry>, f: Field): Boolean {
        val keys = pegasusKeys(f)
        return entries.any { e -> e.keys.any { it.key in keys && !it.isEmpty } }
    }

    private fun pegasusKeyName(f: Field): String = when (f) {
        Field.NAME -> "game"
        Field.DESCRIPTION -> "description"
        Field.GENRE -> "genre"
        Field.RELEASE -> "release"
        Field.DEVELOPER -> "developer"
        Field.PUBLISHER -> "publisher"
        Field.RATING -> "rating"
    }

    /** The lines of `key: value` for [f]; a long description goes on continuation lines, with `.` as the paragraph break. */
    private fun pegasusLines(f: Field, value: String, keyName: String): List<String> = when (f) {
        Field.DESCRIPTION -> {
            val parts = value.split(Regex("\n\\s*\n")).map { it.replace(whitespace, " ").trim() }.filter { it.isNotEmpty() && it != "." }
            buildList {
                add("$keyName: ${parts.firstOrNull().orEmpty()}")
                parts.drop(1).forEach { add("  ."); add("  $it") }
            }
        }
        Field.RATING -> listOf("$keyName: $value%")
        else -> listOf("$keyName: $value")
    }

    private fun pegasusEntry(file: String, meta: GameMeta): List<String> = buildList {
        val shown = file.replace('\\', '/').removePrefix("./")
        add("game: ${meta.text(Field.NAME) ?: shown.substringAfterLast('/').substringBeforeLast('.')}")
        add("file: $shown")
        for (f in listOf(Field.DEVELOPER, Field.PUBLISHER, Field.GENRE, Field.RELEASE, Field.RATING, Field.DESCRIPTION)) {
            meta.text(f)?.let { addAll(pegasusLines(f, it, pegasusKeyName(f))) }
        }
    }

    /** As [esdeMissing] for a Pegasus file; [Field.NAME] is never missing there. */
    fun pegasusMissing(existing: String?, files: Collection<String>): Map<String, Set<Field>>? {
        val all = pegasusFields.toSet()
        if (existing == null || existing.isBlank()) return files.associateWith { all }
        val doc = pegasusParse(existing) ?: return null
        return files.associateWith { file ->
            val entries = findEntries(doc.byKey, pathKey(file)) ?: return@associateWith all
            pegasusFields.filter { !pegasusFilled(entries, it) }.toSet()
        }
    }

    private class LineEdit(val from: Int, val to: Int, val lines: List<String>)

    /** [existing] metadata file (null / blank = none yet) with [items] (file name → details) merged in. */
    fun pegasusMerge(existing: String?, items: Map<String, GameMeta>): Merge {
        val header = "# Game information added by Dogmatix+. Entries you wrote yourself are never overwritten.\n"
        if (existing == null || existing.isBlank()) {
            val fresh = items.entries.filter { it.value.hasData }.distinctBy { pathKey(it.key) }
            if (fresh.isEmpty()) return Merge(null, skipped = items.size)
            val out = StringBuilder(header)
            fresh.forEach { out.append('\n'); pegasusEntry(it.key, it.value).forEach { l -> out.append(l).append('\n') } }
            return Merge(out.toString(), added = fresh.size, skipped = items.size - fresh.size)
        }
        val doc = pegasusParse(existing) ?: return Merge(null, unreadable = true)
        val edits = ArrayList<LineEdit>()
        var added = 0; var filled = 0; var fields = 0; var unchanged = 0; var skipped = 0
        val fresh = LinkedHashMap<String, Pair<String, GameMeta>>()

        val seen = HashSet<String>()
        for ((file, meta) in items) {
            val key = pathKey(file)
            if (!seen.add(key)) continue
            val entries = findEntries(doc.byKey, key)
            if (entries == null) {
                if (meta.hasData) fresh.putIfAbsent(key, file to meta) else skipped++
                continue
            }
            val first = entries.first()
            val appended = ArrayList<String>()
            var n = 0
            for (f in pegasusFields) {
                val value = meta.text(f) ?: continue
                if (pegasusFilled(entries, f)) continue
                val blank = first.keys.firstOrNull { it.key in pegasusKeys(f) && it.isEmpty }
                if (blank != null) edits += LineEdit(blank.line, blank.last + 1, pegasusLines(f, value, blank.raw.trim()))
                else appended += pegasusLines(f, value, pegasusKeyName(f))
                n++
            }
            if (appended.isNotEmpty()) edits += LineEdit(first.keys.last().last + 1, first.keys.last().last + 1, appended)
            if (n > 0) { filled++; fields += n } else unchanged++
        }

        if (fresh.isNotEmpty()) {
            val block = ArrayList<String>()
            fresh.values.forEach { (file, meta) -> block += ""; block += pegasusEntry(file, meta) }
            // A blank line before the first new entry only when the file does not end with one already.
            if (doc.lines.isEmpty() || doc.lines.last().text.isBlank()) block.removeAt(0)
            edits += LineEdit(doc.lines.size, doc.lines.size, block)
            added = fresh.size
        }

        if (edits.isEmpty()) return Merge(null, unchanged = unchanged, skipped = skipped)
        val lines = doc.lines
        // Back to front, so earlier positions stay valid; at one position the later edit goes first
        // and ends up after the earlier one (entry fill-ins before the new entries at the end).
        for ((_, e) in edits.withIndex().sortedWith(compareByDescending<IndexedValue<LineEdit>> { it.value.from }.thenByDescending { it.index })) {
            for (i in e.to - 1 downTo e.from) lines.removeAt(i)
            lines.addAll(e.from, e.lines.map { PLine(it, doc.eol) })
        }
        val out = StringBuilder(doc.bom)
        for (l in lines) out.append(l.text).append(l.eol)
        return Merge(out.toString(), added, filled, fields, unchanged, skipped)
    }
}
