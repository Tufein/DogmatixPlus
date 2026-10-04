package com.cortinadev.dogmatix.util

/** One file of a game as a DAT describes it. Hashes are lower-case hex; null when the DAT has none. */
data class DatRom(val name: String, val size: Long, val crc: String?, val md5: String?, val sha1: String?)

data class DatGame(val name: String, val roms: List<DatRom>)

data class DatFile(val name: String, val version: String, val games: List<DatGame>) {
    val romCount: Int get() = games.sumOf { it.roms.size }
}

/**
 * Reads the DAT files of No-Intro, Redump, TOSEC and MAME: the Logiqx XML layout
 * (`<datafile><game name=…><rom name=… size=… crc=… sha1=…/></game>`, also `<machine>`) and the
 * older ClrMamePro text layout (`game ( name "…" rom ( name "…" size … crc … sha1 … ) )`).
 * Kept free of Android classes so it can be unit-tested.
 */
object DatParser {

    fun parse(text: String): DatFile {
        val trimmed = text.trimStart('﻿', ' ', '\n', '\r', '\t')
        return if (trimmed.startsWith("<")) parseXml(trimmed) else parseClrMamePro(trimmed)
    }

    // ---- Logiqx XML ----------------------------------------------------------------------------

    /**
     * One pass over the tags, without regular expressions: a Redump DAT is 10+ MB, and Android's
     * regex engine (ICU) needed minutes for what takes the JVM a second.
     */
    private fun parseXml(text: String): DatFile {
        var datName = ""
        var datVersion = ""
        val games = ArrayList<DatGame>()
        var gameName: String? = null
        var roms = ArrayList<DatRom>()
        var inHeader = false
        var pos = 0
        val n = text.length
        while (true) {
            val lt = text.indexOf('<', pos)
            if (lt < 0 || lt + 1 >= n) break
            when {
                text.startsWith("<!--", lt) -> { pos = text.indexOf("-->", lt + 4).let { if (it < 0) n else it + 3 }; continue }
                text.startsWith("<![CDATA[", lt) -> { pos = text.indexOf("]]>", lt + 9).let { if (it < 0) n else it + 3 }; continue }
                text[lt + 1] == '?' || text[lt + 1] == '!' -> { pos = text.indexOf('>', lt).let { if (it < 0) n else it + 1 }; continue }
            }
            val gt = tagEnd(text, lt + 1)
            if (gt < 0) break
            val closing = text[lt + 1] == '/'
            val nameStart = if (closing) lt + 2 else lt + 1
            var nameEnd = nameStart
            while (nameEnd < gt && !text[nameEnd].isWhitespace() && text[nameEnd] != '/' && text[nameEnd] != '>') nameEnd++
            val tag = text.substring(nameStart, nameEnd)
            val selfClosing = !closing && text[gt - 1] == '/'
            pos = gt + 1
            if (closing) {
                when (tag) {
                    "game", "machine" -> { gameName?.let { if (roms.isNotEmpty()) games += DatGame(it, roms) }; gameName = null }
                    "header" -> inHeader = false
                }
                continue
            }
            when (tag) {
                "header" -> inHeader = !selfClosing
                "name", "version" -> if (inHeader && !selfClosing) {
                    val close = text.indexOf("</$tag", pos)
                    if (close >= 0) {
                        val value = unescape(text.substring(pos, close)).trim()
                        if (tag == "name") datName = value else datVersion = value
                        pos = close
                    }
                }
                "game", "machine" -> {
                    val name = attributes(text, nameEnd, gt)["name"]
                    if (selfClosing || name == null) gameName = null
                    else { gameName = name; roms = ArrayList() }
                }
                "rom" -> if (gameName != null) rom(attributes(text, nameEnd, gt))?.let { roms += it }
            }
        }
        return DatFile(datName, datVersion, games)
    }

    /** The index of the `>` closing the tag that starts before [from], skipping quoted values. */
    private fun tagEnd(text: String, from: Int): Int {
        var i = from
        var quote = 0.toChar()
        while (i < text.length) {
            val c = text[i]
            if (quote != 0.toChar()) { if (c == quote) quote = 0.toChar() }
            else if (c == '"' || c == '\'') quote = c
            else if (c == '>') return i
            i++
        }
        return -1
    }

    /** The `key="value"` pairs between [from] and [to] (keys lower-case, values unescaped). */
    private fun attributes(text: String, from: Int, to: Int): Map<String, String> {
        val out = HashMap<String, String>(8)
        var i = from
        while (i < to) {
            while (i < to && (text[i].isWhitespace() || text[i] == '/')) i++
            val keyStart = i
            while (i < to && text[i] != '=' && !text[i].isWhitespace() && text[i] != '/') i++
            if (i == keyStart) { i++; continue }
            val key = text.substring(keyStart, i).lowercase()
            while (i < to && text[i].isWhitespace()) i++
            if (i >= to || text[i] != '=') continue
            i++
            while (i < to && text[i].isWhitespace()) i++
            if (i >= to) break
            val q = text[i]
            if (q == '"' || q == '\'') {
                val close = text.indexOf(q, i + 1).let { if (it < 0 || it > to) to else it }
                out[key] = unescape(text.substring(i + 1, close))
                i = close + 1
            } else {
                val valueStart = i
                while (i < to && !text[i].isWhitespace() && text[i] != '/') i++
                out[key] = unescape(text.substring(valueStart, i))
            }
        }
        return out
    }

    private fun rom(a: Map<String, String>): DatRom? {
        val name = a["name"] ?: return null
        return DatRom(name, a["size"]?.toLongOrNull() ?: -1L, hex(a["crc"]), hex(a["md5"]), hex(a["sha1"]))
    }

    private fun unescape(s: String): String {
        if (s.indexOf('&') < 0) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val semi = if (c == '&') s.indexOf(';', i) else -1
            if (semi < 0 || semi - i > 10) { out.append(c); i++; continue }
            val entity = s.substring(i + 1, semi)
            val decoded = when {
                entity == "amp" -> "&"
                entity == "lt" -> "<"
                entity == "gt" -> ">"
                entity == "quot" -> "\""
                entity == "apos" -> "'"
                entity.startsWith("#x") || entity.startsWith("#X") -> entity.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                else -> null
            }
            if (decoded == null) { out.append(c); i++ } else { out.append(decoded); i = semi + 1 }
        }
        return out.toString()
    }

    // ---- ClrMamePro text -------------------------------------------------------------------------

    private val token = Regex(""""((?:[^"\\]|\\.)*)"|(\()|(\))|([^\s()"]+)""")

    private fun parseClrMamePro(text: String): DatFile {
        val tokens = token.findAll(text).map { m ->
            when {
                m.groups[1] != null -> Tok(m.groupValues[1], quoted = true)
                m.groups[2] != null -> Tok("(", false)
                m.groups[3] != null -> Tok(")", false)
                else -> Tok(m.groupValues[4], false)
            }
        }.toList()
        var i = 0
        var datName = ""
        var datVersion = ""
        val games = ArrayList<DatGame>()

        /** Reads a `( key value … )` block starting at the "(" and returns key → value, with nested blocks as lists. */
        fun block(): Pair<Map<String, String>, List<Pair<String, Map<String, String>>>> {
            val fields = HashMap<String, String>()
            val children = ArrayList<Pair<String, Map<String, String>>>()
            i++ // "("
            while (i < tokens.size && !(tokens[i].text == ")" && !tokens[i].quoted)) {
                val key = tokens[i].text.lowercase(); i++
                if (i >= tokens.size) break
                if (tokens[i].text == "(" && !tokens[i].quoted) {
                    children += key to block().first
                } else {
                    fields.putIfAbsent(key, tokens[i].text); i++
                }
            }
            i++ // ")"
            return fields to children
        }

        while (i < tokens.size) {
            val word = tokens[i].text.lowercase(); i++
            if (i < tokens.size && tokens[i].text == "(" && !tokens[i].quoted) {
                val (fields, children) = block()
                when (word) {
                    "clrmamepro" -> { datName = fields["name"].orEmpty(); datVersion = fields["version"].orEmpty() }
                    "game", "machine", "resource" -> {
                        val name = fields["name"] ?: continue
                        val roms = children.filter { it.first == "rom" }.mapNotNull { rom(it.second) }
                        if (roms.isNotEmpty()) games += DatGame(name, roms)
                    }
                }
            }
        }
        return DatFile(datName, datVersion, games)
    }

    private data class Tok(val text: String, val quoted: Boolean)

    private fun hex(value: String?): String? = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
}
