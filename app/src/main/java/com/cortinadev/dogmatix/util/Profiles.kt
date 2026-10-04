package com.cortinadev.dogmatix.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

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

    private const val PBKDF2_ROUNDS = 120_000

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun pbkdf2(pin: String, salt: ByteArray, rounds: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, rounds, 256)).encoded

    /** Old (3.0.0) format: SHA-256 with a fixed salt, 64 hex characters. Still accepted so a PIN set earlier keeps working. */
    private fun legacyHash(pin: String): String =
        hex(MessageDigest.getInstance("SHA-256").digest("dogmatix-profile:$pin".toByteArray()))

    /** `pbkdf2$rounds$salt$hash`: a random salt per PIN and a slow hash, so a copied settings file does not give the PIN away in seconds. The PIN itself is never kept. */
    fun pinHash(pin: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return "pbkdf2$$PBKDF2_ROUNDS$${hex(salt)}$${hex(pbkdf2(pin.trim(), salt, PBKDF2_ROUNDS))}"
    }

    /** True when [pin] belongs to [hash]; an empty [hash] means no PIN is set. */
    fun pinMatches(pin: String, hash: String): Boolean {
        if (hash.isEmpty()) return true
        val entered = pin.trim()
        if (!hash.startsWith("pbkdf2$")) return legacyHash(entered) == hash
        val parts = hash.split('$')
        if (parts.size != 4) return false
        val rounds = parts[1].toIntOrNull() ?: return false
        val expected = runCatching { unhex(parts[3]) }.getOrNull() ?: return false
        val actual = runCatching { pbkdf2(entered, unhex(parts[2]), rounds) }.getOrNull() ?: return false
        return MessageDigest.isEqual(expected, actual)
    }
}
