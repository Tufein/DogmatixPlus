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

    private val gameBlock = Regex("""<(game|machine)\b([^>]*?)(/>|>(.*?)</\1\s*>)""", RegexOption.DOT_MATCHES_ALL)
    private val romTag = Regex("""<rom\b([^>]*?)/?>""", RegexOption.DOT_MATCHES_ALL)
    private val attribute = Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*("([^"]*)"|'([^']*)')""")

    private fun parseXml(text: String): DatFile {
        val header = Regex("""<header>(.*?)</header>""", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1).orEmpty()
        fun headerField(tag: String) = Regex("""<$tag>(.*?)</$tag>""", RegexOption.DOT_MATCHES_ALL).find(header)?.groupValues?.get(1)?.let(::unescape)?.trim().orEmpty()
        val games = gameBlock.findAll(text).mapNotNull { m ->
            val name = attributes(m.groupValues[2])["name"] ?: return@mapNotNull null
            val roms = romTag.findAll(m.groupValues[4]).mapNotNull { r -> rom(attributes(r.groupValues[1])) }.toList()
            if (roms.isEmpty()) null else DatGame(name, roms)
        }.toList()
        return DatFile(headerField("name"), headerField("version"), games)
    }

    private fun attributes(text: String): Map<String, String> =
        attribute.findAll(text).associate { it.groupValues[1].lowercase() to unescape(it.groupValues[3].ifEmpty { it.groupValues[4] }) }

    private fun rom(a: Map<String, String>): DatRom? {
        val name = a["name"] ?: return null
        return DatRom(name, a["size"]?.toLongOrNull() ?: -1L, hex(a["crc"]), hex(a["md5"]), hex(a["sha1"]))
    }

    private fun unescape(s: String): String = s
        .replace(Regex("""&#x([0-9A-Fa-f]+);""")) { it.groupValues[1].toInt(16).toChar().toString() }
        .replace(Regex("""&#(\d+);""")) { it.groupValues[1].toInt().toChar().toString() }
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

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
