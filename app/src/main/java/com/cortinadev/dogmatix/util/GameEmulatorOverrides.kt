package com.cortinadev.dogmatix.util

import java.text.Normalizer
import java.util.Locale

/** A game's emulator follows its title and structural disc, including after a version upgrade. */
object GameEmulatorOverrides {
    private fun normalized(value: String) = Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
        .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    /** Ordered titles keep "World Super" separate from "Super World"; console and disc stay scoped. */
    fun identity(consoleId: String, fileName: String): String {
        val readable = VersionPicker.readable(fileName)
        val title = normalized(GameTitleCleaner.clean(readable)).ifBlank { normalized(readable) }
        val parts = listOf(normalized(consoleId), title, VersionPreference.partKey(readable))
        // Length prefixes prevent separators inside source names from merging two identities.
        return parts.joinToString("") { "${it.length}:$it" }
    }

    fun storageKey(profileId: String, consoleId: String, fileName: String): String =
        (if (profileId.isBlank()) "" else "personal:$profileId:") + "game:" + identity(consoleId, fileName)

    data class Resolution<T>(val handler: T?, val fromGame: Boolean, val missingGameOverride: Boolean)

    /** An unavailable explicit package/core never turns into another variant of that emulator. */
    fun <T> resolve(gameOverride: String?, consoleDefault: String?, handlers: List<T>, keyOf: (T) -> String, packageOf: (T) -> String): Resolution<T> {
        val own = GameLaunchKeys.resolve(gameOverride, handlers, keyOf, packageOf)
        if (own != null) return Resolution(own, fromGame = true, missingGameOverride = false)
        return Resolution(GameLaunchKeys.resolve(consoleDefault, handlers, keyOf, packageOf), fromGame = false,
            missingGameOverride = !gameOverride.isNullOrBlank())
    }
}
