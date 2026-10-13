package com.cortinadev.dogmatix.util

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.LocalDate

/**
 * Optional, reviewed directory sources. Importing a choice merges it; it never replaces sources.
 *
 * Schema 2 lists the providers that publish the content (name, homepage, where the list comes
 * from, what kind of permission covers it) and every entry names its provider. A provider is
 * only accepted when it is also in [TRUSTED] with the same base address and provenance, so a
 * changed asset cannot point the app at a server nobody reviewed. Every entry is a plain HTTP
 * directory listing below that base address: the kind of source the scanner reads (a table of
 * links, files only, sub-folders are not walked). Schema 1 (one Libretro list) is still read.
 */
object RomsetCatalog {
    const val ASSET = "romsets.json"
    const val MAX_BYTES = 256 * 1024
    const val MAX_PROVIDERS = 16
    const val MAX_ENTRIES = 256
    /** Entries one provider may list. */
    const val MAX_PER_PROVIDER = 64
    private val slug = Regex("[a-z0-9]+(?:_[a-z0-9]+)*")

    /** What allows the provider to hand the files out. Shown to the user next to the provider. */
    enum class Licence(val key: String) {
        /** Free content the provider itself publishes for download (homebrew, freeware, demos, test software). */
        PUBLISHED("published"),
        /** Explicitly placed in the public domain by the authors. */
        PUBLIC_DOMAIN("public_domain"),
        /** Released by the authors under a free licence that allows redistribution. */
        FREE_LICENCE("free_licence");

        companion object { fun of(key: String): Licence? = entries.firstOrNull { it.key == key } }
    }

    /** A reviewed provider: the only base address and provenance its entries may use. */
    data class Trusted(val baseUrl: String, val provenance: String)

    /** Providers that have been reviewed. A provider in the asset but not here is refused. */
    val TRUSTED: Map<String, Trusted> = mapOf(
        "libretro" to Trusted("https://buildbot.libretro.com/assets/cores/", "https://github.com/libretro/libretro-content")
    )

    data class Provider(
        val id: String,
        val name: String,
        val homepage: String,
        val provenance: String,
        val licence: Licence,
        val baseUrl: String,
        val checkedAt: String
    )

    data class Entry(
        val id: String,
        val manufacturerId: String,
        val manufacturerName: String,
        val consoleKey: String,
        val consoleName: String,
        val shortName: String,
        val aliases: List<String>,
        val folder: String,
        val fileCount: Int,
        val providerId: String = "libretro",
        val baseUrl: String = "https://buildbot.libretro.com/assets/cores/"
    ) {
        /** Unique within the catalogue: two providers may offer the same console. */
        val key: String get() = "$providerId:$id"
        val url: String get() = baseUrl.toHttpUrl().newBuilder().addPathSegment(folder).addPathSegment("").build().toString()
    }

    data class Catalog(val checkedAt: String, val providers: List<Provider>, val entries: List<Entry>) {
        fun provider(id: String): Provider? = providers.firstOrNull { it.id == id }
        /** Entries of one provider (all when null), in the catalogue's order. */
        fun entriesOf(providerId: String?): List<Entry> = if (providerId == null) entries else entries.filter { it.providerId == providerId }
    }

    fun parse(json: String, trusted: Map<String, Trusted> = TRUSTED): Catalog {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Catalog too large" }
        val root = JsonParser.parseString(json).asJsonObject
        val schema = integer(root, "schema")
        require(schema == 1 || schema == 2) { "Unsupported catalog" }
        val checkedAt = date(string(root, "checkedAt"))
        val providers = if (schema == 1) listOf(legacyProvider(root, checkedAt, trusted)) else providers(root, checkedAt, trusted)
        val byId = providers.associateBy { it.id }
        val array = root.getAsJsonArray("entries")
        require(array != null && array.size() in 1..MAX_ENTRIES)
        val entries = array.map { element ->
            val obj = element.asJsonObject
            val provider = if (schema == 1) providers.single() else byId[string(obj, "provider")] ?: throw IllegalArgumentException("Unknown provider")
            val maker = string(obj, "manufacturerId").also { require(slug.matches(it)) }
            val key = string(obj, "consoleKey").also { require(slug.matches(it)) }
            val id = string(obj, "id").also { require(it == "${maker}_${key}") }
            val folder = string(obj, "folder").also {
                require(it != "." && it != ".." && it.none { c -> c == '/' || c == '\\' || c.isISOControl() })
            }
            val aliases = obj.getAsJsonArray("aliases").map { alias ->
                require(alias.isJsonPrimitive && alias.asJsonPrimitive.isString)
                alias.asString.also { require(it.isNotBlank() && it.length <= 64 && it.none(Char::isISOControl)) }
            }.distinct().also { require(it.size <= 16) }
            Entry(id, maker, string(obj, "manufacturerName"), key, string(obj, "consoleName"),
                string(obj, "shortName"), aliases, folder, integer(obj, "fileCount").also { require(it in 1..100_000) },
                provider.id, provider.baseUrl)
        }
        require(entries.map { it.key }.distinct().size == entries.size)
        require(entries.map { it.url }.distinct().size == entries.size)
        require(entries.groupingBy { it.providerId }.eachCount().values.all { it <= MAX_PER_PROVIDER })
        // Every entry stays below its provider's reviewed address.
        entries.forEach { require(it.url.startsWith(it.baseUrl) && it.url.length > it.baseUrl.length) }
        // A provider without entries is not shown.
        val used = providers.filter { p -> entries.any { it.providerId == p.id } }
        return Catalog(checkedAt, used, entries.sortedWith(compareBy({ it.consoleName.lowercase() }, { it.providerId })))
    }

    private fun legacyProvider(root: JsonObject, checkedAt: String, trusted: Map<String, Trusted>): Provider {
        val publisher = string(root, "publisher")
        val provenance = string(root, "provenance")
        val reviewed = trusted["libretro"]
        require(publisher == "Libretro" && reviewed != null && provenance == reviewed.provenance)
        return Provider("libretro", publisher, "https://www.libretro.com/", provenance, Licence.PUBLISHED, reviewed.baseUrl, checkedAt)
    }

    private fun providers(root: JsonObject, checkedAt: String, trusted: Map<String, Trusted>): List<Provider> {
        val array = root.getAsJsonArray("providers")
        require(array != null && array.size() in 1..MAX_PROVIDERS)
        val providers = array.map { element ->
            val obj = element.asJsonObject
            val id = string(obj, "id").also { require(slug.matches(it)) }
            val reviewed = trusted[id] ?: throw IllegalArgumentException("Untrusted provider")
            val baseUrl = string(obj, "baseUrl")
            val provenance = string(obj, "provenance")
            require(baseUrl == reviewed.baseUrl && provenance == reviewed.provenance) { "Provider address changed" }
            require(baseUrl.endsWith('/') && https(baseUrl))
            val homepage = string(obj, "homepage").also { require(https(it)) }
            val licence = Licence.of(string(obj, "licence")) ?: throw IllegalArgumentException("Unknown licence")
            val checked = obj.get("checkedAt")?.let { date(string(obj, "checkedAt")) } ?: checkedAt
            Provider(id, string(obj, "name"), homepage, provenance, licence, baseUrl, checked)
        }
        require(providers.map { it.id }.distinct().size == providers.size)
        return providers
    }

    private fun https(url: String): Boolean = url.toHttpUrlOrNull()?.let { it.isHttps && it.username.isEmpty() && it.password.isEmpty() } == true

    private fun date(value: String): String = value.also { LocalDate.parse(it) }

    // ---- Console mapping ------------------------------------------------------------------------

    /** A console the user already has (its name, chip label and folder aliases as saved). */
    data class ConsoleRef(val id: String, val name: String, val shortName: String = "", val aliases: List<String> = emptyList())

    /** Where an entry goes: an existing console of the user, or a new one with the entry's id. */
    data class Mapping(val consoleId: String, val consoleName: String, val existing: Boolean)

    /**
     * The console [entry] is added to, through the same alias rules the download folders use
     * ([ConsoleFolderAliases]): the console with the entry's id; else the one console whose
     * name, chip label, key or folder aliases name the same system (e.g. a hand-made "Game Boy"
     * console with id `nintendo_game_boy` for the catalogue's `nintendo_gameboy`). When several
     * consoles match and none is clearly the one, a new console is made rather than guessing.
     */
    fun mapConsole(entry: Entry, consoles: List<ConsoleRef>): Mapping {
        consoles.firstOrNull { it.id == entry.id }?.let { return Mapping(it.id, it.name, existing = true) }
        val wanted = entryNames(entry)
        val matches = consoles.filter { (namesOf(it) intersect wanted).isNotEmpty() }
        val pick = when {
            matches.size == 1 -> matches.single()
            matches.size > 1 -> matches.filter { ConsoleFormatter.consoleKey(it.id) == entry.consoleKey }.singleOrNull()
                ?: matches.filter { it.id.substringBefore('_') == entry.manufacturerId }.singleOrNull()
            else -> null
        }
        return pick?.let { Mapping(it.id, it.name, existing = true) } ?: Mapping(entry.id, entry.consoleName, existing = false)
    }

    /** Normalized names that identify the entry's system. */
    fun entryNames(entry: Entry): Set<String> =
        (listOf(entry.consoleKey, entry.consoleName, entry.shortName) + entry.aliases + ConsoleFolderAliases.defaultAliasesFor(entry.id))
            .map(ConsoleFolderAliases::normalize).filter { it.isNotEmpty() }.toSet()

    private fun namesOf(console: ConsoleRef): Set<String> {
        val key = ConsoleFormatter.consoleKey(console.id)
        return (listOf(key, console.name, console.shortName.ifBlank { ConsoleFormatter.getDefaultShortName(console.id) }) +
            console.aliases + ConsoleFolderAliases.defaultAliasesFor(console.id))
            .map(ConsoleFolderAliases::normalize).filter { it.isNotEmpty() }.toSet()
    }

    // ---- Dialog filters -------------------------------------------------------------------------

    /**
     * Entries shown for [providerId] (all when null) and a search [query] over the console name,
     * short name, manufacturer, aliases and provider name.
     */
    fun filter(catalog: Catalog, providerId: String?, query: String): List<Entry> {
        val q = query.trim()
        val folded = ConsoleFolderAliases.normalize(q)
        return catalog.entriesOf(providerId).filter { e ->
            q.isEmpty() || "${e.consoleName} ${e.shortName} ${e.manufacturerName} ${catalog.provider(e.providerId)?.name.orEmpty()}".contains(q, ignoreCase = true) ||
                (folded.isNotEmpty() && e.aliases.any { ConsoleFolderAliases.normalize(it) == folded })
        }
    }

    /** Entries grouped by provider, providers in catalogue order, empty groups left out. */
    fun groups(catalog: Catalog, entries: List<Entry>): List<Pair<Provider, List<Entry>>> =
        catalog.providers.mapNotNull { p -> entries.filter { it.providerId == p.id }.takeIf { it.isNotEmpty() }?.let { p to it } }

    private fun string(obj: JsonObject, key: String): String {
        val value = obj.get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString.also { require(it.isNotBlank() && it.length <= 160 && it.none(Char::isISOControl)) }
    }

    private fun integer(obj: JsonObject, key: String): Int {
        val value = obj.get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        return value.asString.toIntOrNull() ?: throw IllegalArgumentException("Invalid $key")
    }
}
