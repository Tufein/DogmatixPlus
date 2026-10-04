package com.cortinadev.dogmatix.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest

/**
 * A profile hides consoles and tags from the library (and from what can be downloaded through
 * it) — for a child, or for a "couch" profile without the odd stuff. Leaving a profile can be
 * guarded with a PIN. No profile active = everything visible.
 */
data class Profile(
    val id: String,
    val name: String,
    val hiddenConsoles: Set<String> = emptySet(),
    /** Library tags (any kind) whose games are hidden, e.g. `Adult`, `Hack`, `Japan`. */
    val hiddenTags: Set<String> = emptySet()
)

/** What the active profile hides; [NONE] when no profile is active. */
data class LibraryRestrictions(val hiddenConsoles: Set<String> = emptySet(), val hiddenTags: Set<String> = emptySet()) {
    val active: Boolean get() = hiddenConsoles.isNotEmpty() || hiddenTags.isNotEmpty()
    fun allows(consoleId: String, tags: Collection<String>): Boolean =
        consoleId !in hiddenConsoles && tags.none { t -> hiddenTags.any { it.equals(t, ignoreCase = true) } }
    companion object { val NONE = LibraryRestrictions() }
}

object Profiles {
    fun toJson(profiles: List<Profile>): String = JsonArray().apply {
        profiles.forEach { p ->
            add(JsonObject().apply {
                addProperty("id", p.id); addProperty("name", p.name)
                add("hiddenConsoles", JsonArray().apply { p.hiddenConsoles.sorted().forEach(::add) })
                add("hiddenTags", JsonArray().apply { p.hiddenTags.sorted().forEach(::add) })
            })
        }
    }.toString()

    fun fromJson(json: String?): List<Profile> = runCatching {
        JsonParser.parseString(json ?: "[]").asJsonArray.mapNotNull { el ->
            val o = el.asJsonObject
            val id = o.get("id")?.asString ?: return@mapNotNull null
            Profile(
                id, o.get("name")?.asString.orEmpty(),
                o.getAsJsonArray("hiddenConsoles")?.map { it.asString }?.toSet().orEmpty(),
                o.getAsJsonArray("hiddenTags")?.map { it.asString }?.toSet().orEmpty()
            )
        }
    }.getOrDefault(emptyList())

    fun restrictionsOf(profiles: List<Profile>, activeId: String): LibraryRestrictions =
        profiles.firstOrNull { it.id == activeId }?.let { LibraryRestrictions(it.hiddenConsoles, it.hiddenTags.map { t -> t.trim() }.filter { t -> t.isNotEmpty() }.toSet()) }
            ?: LibraryRestrictions.NONE

    /** Tags typed as "Adult, Hack ; Japan" → a set. */
    fun parseTags(text: String): Set<String> = text.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** Salted SHA-256 of a PIN, as stored; the PIN itself is never kept. */
    fun pinHash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("dogmatix-profile:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    fun pinMatches(pin: String, hash: String): Boolean = hash.isEmpty() || pinHash(pin) == hash
}
