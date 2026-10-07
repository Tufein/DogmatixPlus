package com.cortinadev.dogmatix.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

/** What decides between two versions of the same score and name: nothing, the smaller or the larger file. */
enum class SizeTieBreak { NONE, SMALLER, LARGER }

/**
 * Which version of a game the user wants: languages and regions in order of preference, and the
 * rules that mark a release down. [VersionCompare] ranks versions by it. Pure JVM.
 *
 * [languages] are upper-case codes ("NL", "EN"), best first; [regions] are region tags as files
 * write them ("Europe", "USA"), best first. Without anything set by the user the app uses
 * [VersionPreferences.defaultFor], which picks exactly as the favourite languages always did.
 *
 * The companion keeps the per-game fixed version ([key] / [pick]): a version the user fixed for one
 * game always wins over any ranking.
 */
data class VersionPreference(
    val languages: List<String> = emptyList(),
    val regions: List<String> = VersionPreferences.DEFAULT_REGIONS,
    /** A beta or prototype goes below a final release. */
    val preferFinal: Boolean = true,
    /** A later revision (Rev 1, v1.1) goes above an earlier one. */
    val preferLatestRevision: Boolean = true,
    /** `[!]` verified dumps go up, bad dumps and overdumps down. */
    val preferVerifiedDump: Boolean = true,
    /** Hacks, fan translations, demos, unlicensed and alternate releases go down. */
    val avoidUnofficial: Boolean = true,
    val sizeTieBreak: SizeTieBreak = SizeTieBreak.NONE
) {
    companion object {
        /** Include the disc identifier: fixing disc 1 must never redirect disc 2 to it. */
        fun key(consoleId: String, name: String): String {
            val disc = Regex("(?i)\\b(?:disc|disk|cd|side)\\s*([0-9a-z]+)").find(name)?.groupValues?.get(1)?.lowercase().orEmpty()
            return consoleId + "|" + GameTitleCleaner.words(name).sorted().joinToString(" ") + "|" + disc
        }

        /** The fixed version [preferred] when it is among [candidates], else the best by [regions] and [languages]. */
        fun pick(candidates: List<VersionPicker.Candidate>, regions: List<String>, languages: Set<String>, preferred: String?): VersionPicker.Candidate? =
            pick(candidates, VersionPreferences.of(regions, languages), preferred)

        /** The fixed version [preferred] when it is among [candidates], else the best by [preference]. */
        fun pick(candidates: List<VersionPicker.Candidate>, preference: VersionPreference, preferred: String?): VersionPicker.Candidate? =
            candidates.firstOrNull { it.id == preferred } ?: VersionPicker.best(candidates, preference)
    }
}

/** A console's own order of regions and/or languages; a null list follows the preference for all consoles. */
data class ConsoleOverride(val regions: List<String>? = null, val languages: List<String>? = null) {
    val isEmpty: Boolean get() = regions == null && languages == null
}

/** Defaults, wording, list editing and the stored form of a [VersionPreference]. */
object VersionPreferences {

    val DEFAULT_REGIONS = listOf("World", "Europe", "USA", "Japan", "Australia", "Asia", "Korea", "Brazil")

    /** Regions the preference screen offers (the order is the order of the "add" list). */
    val KNOWN_REGIONS = listOf(
        "World", "Europe", "USA", "Japan", "Australia", "Asia", "Korea", "Brazil", "Canada", "UK", "Germany", "France",
        "Spain", "Italy", "Netherlands", "Scandinavia", "Russia", "China", "Mexico", "Poland", "Portugal"
    )

    /** Languages the preference screen offers. */
    val KNOWN_LANGUAGES = listOf(
        "EN", "NL", "DE", "FR", "ES", "IT", "PT", "SV", "DA", "NO", "FI", "PL", "RU", "CS", "EL", "TR", "JA", "KO", "ZH"
    )

    /** "NORTH AMERICA" / "usa" → "North America" / "USA". */
    fun prettyRegion(tag: String): String {
        val u = tag.trim().uppercase(Locale.ROOT)
        if (u == "USA" || u == "UK") return u
        return u.lowercase(Locale.ROOT).split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } }
    }

    /** "JP" and "JPN" are the same preference as "JA"; everything upper case. */
    fun canonicalLanguage(code: String): String = when (val u = code.trim().uppercase(Locale.ROOT)) {
        "JP", "JPN" -> "JA"
        else -> u
    }

    /** The preference behind an explicit region order and a set of languages (in the set's own order). */
    fun of(regions: List<String>, languages: Set<String>): VersionPreference =
        VersionPreference(languages = cleanLanguages(languages.toList()), regions = cleanRegions(regions))

    /**
     * What applies while the user has not set a preference: exactly the favourite languages and the
     * region order [VersionPicker.regionPreference] derives from them (as the app always chose). The
     * languages are ordered so the first choice is stable: the app's and the device's language
     * first, then English, then the rest alphabetically.
     */
    fun defaultFor(favourites: Set<String>, appLanguage: String?, deviceLanguage: String?): VersionPreference {
        val favs = cleanLanguages(favourites.toList())
        val first = listOfNotNull(appLanguage, deviceLanguage, "EN").map(::canonicalLanguage).filter { it in favs }.distinct()
        return VersionPreference(languages = first + favs.filter { it !in first }.sorted(), regions = VersionPicker.regionPreference(favs.toSet()))
    }

    /** [base] with a console's own order laid over its regions / languages. */
    fun withOverride(base: VersionPreference, override: ConsoleOverride?): VersionPreference =
        if (override == null || override.isEmpty) base
        else base.copy(regions = override.regions ?: base.regions, languages = override.languages ?: base.languages)

    /** "Dutch" for "NL" in [locale]. */
    fun languageName(code: String, locale: Locale = Locale.getDefault()): String {
        val lower = canonicalLanguage(code).lowercase(Locale.ROOT)
        val name = Locale(lower).getDisplayLanguage(locale)
        return if (name.isBlank() || name.equals(lower, ignoreCase = true)) code.uppercase(Locale.ROOT) else name.replaceFirstChar { it.titlecase(locale) }
    }

    /** A one-line description: "World, Europe, USA · Dutch, English". */
    fun summary(p: VersionPreference, locale: Locale = Locale.getDefault(), max: Int = 3): String {
        val regions = p.regions.take(max).joinToString(", ")
        val languages = p.languages.take(max).joinToString(", ") { languageName(it, locale) }
        return listOf(regions, languages).filter { it.isNotBlank() }.joinToString(" · ")
    }

    // ---- Editing the order lists (by value: a stale index after a double press cannot hit the wrong entry) ----

    /** [list] with [item] moved by [delta] places (up = -1); unchanged when it is not there or cannot move. */
    fun move(list: List<String>, item: String, delta: Int): List<String> {
        val index = list.indexOfFirst { it.equals(item, ignoreCase = true) }
        val to = index + delta
        if (index < 0 || to !in list.indices) return list
        return list.toMutableList().also { val moved = it.removeAt(index); it.add(to, moved) }
    }

    fun remove(list: List<String>, item: String): List<String> = list.filterNot { it.equals(item, ignoreCase = true) }

    /** [item] at the end of [list] unless it is already there (any case). */
    fun add(list: List<String>, item: String): List<String> = if (list.any { it.equals(item, ignoreCase = true) }) list else list + item

    /** Language codes upper case and canonical, blanks and repeats dropped, order kept. */
    fun cleanLanguages(values: List<String>): List<String> = values.map(::canonicalLanguage).filter { it.isNotEmpty() }.distinct()

    /** Regions trimmed, blanks and repeats (any case) dropped, order kept. */
    fun cleanRegions(values: List<String>): List<String> {
        val out = ArrayList<String>()
        values.map { it.trim() }.filter { it.isNotEmpty() }.forEach { v -> if (out.none { it.equals(v, ignoreCase = true) }) out += v }
        return out
    }

    /** The sample game of the preference screen: names as the sources write them, with sizes. */
    val SAMPLE_GAME: List<VersionCompare.Version> = listOf(
        "Mario Kart (Europe) (En,Fr,De) (Rev 1) [!].gba" to 8_388_608L,
        "Mario Kart (Europe) (En,Fr,De).gba" to 8_388_608L,
        "Mario Kart (USA).gba" to 8_388_608L,
        "Mario Kart (Japan) (Ja).gba" to 8_388_608L,
        "Mario Kart (Europe) (En,Fr,De) (Beta).gba" to 8_126_464L
    ).map { (name, size) -> VersionCompare.Version(name, name, size = size) }

    // ---- Stored form -----------------------------------------------------------------------------

    /** The strings of array [key]; null when it is missing or not an array (one bad value is skipped). */
    private fun JsonObject.strings(key: String): List<String>? {
        val element = get(key)?.takeUnless { it.isJsonNull } ?: return null
        if (!element.isJsonArray) return null
        return element.asJsonArray.mapNotNull { e -> runCatching { e.asString }.getOrNull() }
    }

    private fun JsonObject.flag(key: String): Boolean =
        get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asBoolean }.getOrNull() } ?: true

    private fun list(values: List<String>) = JsonArray().apply { values.forEach { add(it) } }

    fun toJson(p: VersionPreference): String = JsonObject().apply {
        add("lang", list(cleanLanguages(p.languages)))
        add("reg", list(cleanRegions(p.regions)))
        addProperty("final", p.preferFinal)
        addProperty("rev", p.preferLatestRevision)
        addProperty("dump", p.preferVerifiedDump)
        addProperty("avoid", p.avoidUnofficial)
        addProperty("size", p.sizeTieBreak.name)
    }.toString()

    fun fromJson(json: String?): VersionPreference? = runCatching {
        val o = JsonParser.parseString(json ?: return null).asJsonObject
        val size = o.get("size")?.takeUnless { it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }
        VersionPreference(
            languages = cleanLanguages(o.strings("lang").orEmpty()),
            regions = o.strings("reg")?.let(::cleanRegions) ?: DEFAULT_REGIONS,
            preferFinal = o.flag("final"),
            preferLatestRevision = o.flag("rev"),
            preferVerifiedDump = o.flag("dump"),
            avoidUnofficial = o.flag("avoid"),
            sizeTieBreak = SizeTieBreak.entries.firstOrNull { it.name == size } ?: SizeTieBreak.NONE
        )
    }.getOrNull()

    fun overridesToJson(overrides: Map<String, ConsoleOverride>): String = JsonObject().apply {
        overrides.filterValues { !it.isEmpty }.forEach { (console, o) ->
            add(console, JsonObject().apply {
                o.regions?.let { add("reg", list(cleanRegions(it))) }
                o.languages?.let { add("lang", list(cleanLanguages(it))) }
            })
        }
    }.toString()

    /** Console id → its override; an entry that cannot be read is dropped on its own, the others stay. */
    fun overridesFromJson(json: String?): Map<String, ConsoleOverride> {
        val root: JsonElement = runCatching { JsonParser.parseString(json ?: return emptyMap()) }.getOrNull() ?: return emptyMap()
        if (!root.isJsonObject) return emptyMap()
        val out = LinkedHashMap<String, ConsoleOverride>()
        for ((console, value) in root.asJsonObject.entrySet()) {
            val entry = runCatching {
                val o = value.asJsonObject
                ConsoleOverride(o.strings("reg")?.let(::cleanRegions), o.strings("lang")?.let(::cleanLanguages))
            }.getOrNull() ?: continue
            if (console.isNotBlank() && !entry.isEmpty) out[console] = entry
        }
        return out
    }
}
