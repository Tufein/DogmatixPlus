package com.cortinadev.dogmatix.util

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

/** Why a game cannot be handed over to another handheld (yet); each comes with its own explanation. */
enum class HandoffBlock {
    /** RomM (address, login) is not set up: the save travels through it. */
    NO_ROMM,
    /** The game is not in the RomM library, so RomM has nowhere to keep its save. */
    NOT_ON_ROMM,
    /** No emulator is chosen or installed for this game. */
    NO_EMULATOR,
    /** The game opens in an app the save sync does not know. */
    UNKNOWN_APP,
    /** A known emulator (or RetroArch core) whose saves the sync cannot reach (Dolphin, the PPSSPP core…). */
    SAVES_NOT_SUPPORTED,
    /** RetroArch: its saves folder is not picked in Save sync. */
    NO_RETROARCH_FOLDER,
    /** A supported standalone emulator whose saves folder is not picked in Save sync. */
    NO_EMULATOR_FOLDER
}

/** Whether this game's emulator + save type can be handed over, and how. */
sealed interface HandoffSupport {
    /**
     * It can. [emulatorLabel] (and the RetroArch [coreLabel]) must be used on the other handheld too;
     * [folderLabel] is the Save sync folder of a standalone emulator, whose [layout] says what kind
     * of save travels (a file, a memory card, a save folder).
     */
    data class Supported(
        val emulatorId: String,
        val emulatorLabel: String,
        val core: String? = null,
        val coreLabel: String? = null,
        val folderLabel: String? = null,
        val layout: SaveFolderLayout = SaveFolderLayout.FILES
    ) : HandoffSupport

    /** It cannot; [emulatorLabel] names the app when one is known, [folderHint] where its saves folder usually is. */
    data class Unsupported(val block: HandoffBlock, val emulatorLabel: String? = null, val folderHint: String? = null) : HandoffSupport
}

/**
 * "Continue on another handheld": which emulator and save combinations the hand-off supports, and
 * what the other device needs. The save itself travels through RomM with the checked transfer of
 * [SaveHandoff]; nothing here moves or deletes anything. Pure JVM for the tests.
 */
object HandoffGuide {
    const val RETROARCH = "retroarch"

    /**
     * RetroArch cores that keep in-game saves outside RetroArch's saves folder (in the system
     * folder or their own memory card / NAND images), where the sync cannot see them.
     */
    private val coresOutsideSaves = setOf("ppsspp", "dolphin", "citra", "pcsx2")

    /**
     * Whether the game of [consoleId] that starts with [emulatorId] (+ RetroArch [core]) can be handed
     * over. [appLabel] names an app outside the catalogue (null when no app is chosen at all).
     */
    fun support(
        consoleId: String,
        emulatorId: String?,
        core: String?,
        appLabel: String?,
        folders: List<EmulatorSaveFolder>,
        retroArchSavesFolder: Boolean,
        rommReady: Boolean,
        onRomm: Boolean
    ): HandoffSupport {
        if (!rommReady) return HandoffSupport.Unsupported(HandoffBlock.NO_ROMM)
        if (!onRomm) return HandoffSupport.Unsupported(HandoffBlock.NOT_ON_ROMM)
        val emulator = emulatorId?.let(EmulatorCatalog::byId)
        if (emulator == null) {
            return if (appLabel.isNullOrBlank()) HandoffSupport.Unsupported(HandoffBlock.NO_EMULATOR)
            else HandoffSupport.Unsupported(HandoffBlock.UNKNOWN_APP, appLabel)
        }
        if (emulator.id == RETROARCH) {
            val chosen = core?.takeIf { it.isNotBlank() } ?: EmulatorCatalog.systemOf(consoleId)?.cores?.firstOrNull()
            val coreLabel = chosen?.let(EmulatorCatalog::coreLabel)
            val label = coreLabel?.let { "${emulator.label} ($it)" } ?: emulator.label
            if (chosen == null || chosen in coresOutsideSaves) return HandoffSupport.Unsupported(HandoffBlock.SAVES_NOT_SUPPORTED, label)
            if (!retroArchSavesFolder) return HandoffSupport.Unsupported(HandoffBlock.NO_RETROARCH_FOLDER, label)
            return HandoffSupport.Supported(emulator.id, emulator.label, chosen, coreLabel)
        }
        val preset = EmulatorSaveFolders.presets.firstOrNull { it.emulatorId == emulator.id }
            ?: return HandoffSupport.Unsupported(HandoffBlock.SAVES_NOT_SUPPORTED, emulator.label)
        val folder = EmulatorSaveFolders.foldersFor(emulator.id, consoleId, folders).firstOrNull()
            ?: return HandoffSupport.Unsupported(HandoffBlock.NO_EMULATOR_FOLDER, emulator.label, preset.folderHint)
        return HandoffSupport.Supported(emulator.id, emulator.label, folderLabel = folder.label, layout = folder.layout)
    }

    /** The site a game's source is on (`https://example.org/files/…` → `example.org`), for "download it from the same source". */
    fun sourceLabel(sourceUrl: String?): String? {
        val text = sourceUrl?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text.startsWith("magnet:", ignoreCase = true)) return null
        return runCatching { URI(text).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() }
    }

    /**
     * A short code for the saves of one hand-off, the same on both handhelds when they hold the
     * same bytes: 8 characters (`K7PQ-2M4X`) from the SHA-256 of every save's SHA-256, in any order.
     * Null when there is no save.
     */
    fun code(saveHashes: Collection<String>): String? {
        if (saveHashes.isEmpty()) return null
        val joined = saveHashes.map { it.lowercase() }.sorted().joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
        var bits = 0L
        for (i in 0 until 5) bits = (bits shl 8) or (digest[i].toLong() and 0xFF)
        val chars = (0 until 8).map { i -> CODE_ALPHABET[((bits shr (35 - i * 5)) and 31).toInt()] }.joinToString("")
        return chars.take(4) + "-" + chars.drop(4)
    }

    /** Crockford's base 32: no I, L, O or U, so a code read aloud or typed is not mistaken. */
    private const val CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** Codes compare without the dash, spaces or case. */
    fun sameCode(a: String?, b: String?): Boolean {
        fun clean(s: String?) = s.orEmpty().uppercase().filter { it.isLetterOrDigit() }
        return clean(a).isNotEmpty() && clean(a) == clean(b)
    }
}

/**
 * `dogmatix://handoff?console=<console id>&file=<file name>&code=<K7PQ-2M4X>`: opened on the
 * other handheld (as a link, or from the QR code), it shows that game's "Continue here" with the
 * code to compare.
 */
object HandoffLink {
    const val HOST = "handoff"

    data class Request(val consoleId: String, val fileName: String, val code: String?)

    fun build(request: Request): String = buildString {
        append(DeepLinkParser.SCHEME).append("://").append(HOST)
        append("?console=").append(encode(request.consoleId))
        append("&file=").append(encode(request.fileName))
        request.code?.takeIf { it.isNotBlank() }?.let { append("&code=").append(encode(it)) }
    }

    fun parse(uri: String?): Request? {
        if (uri.isNullOrBlank() || uri.length > 4096) return null
        val parsed = runCatching { URI(uri.trim()) }.getOrNull() ?: return null
        if (!parsed.scheme.equals(DeepLinkParser.SCHEME, ignoreCase = true) || !parsed.host.equals(HOST, ignoreCase = true)) return null
        val params = parsed.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { pair ->
            val eq = pair.indexOf('=')
            decode(if (eq < 0) pair else pair.substring(0, eq)).lowercase() to decode(if (eq < 0) "" else pair.substring(eq + 1))
        }
        val console = params["console"]?.trim().orEmpty()
        val file = params["file"]?.trim().orEmpty()
        // A file name is one name: no folders, nothing that could walk out of the library.
        if (console.isEmpty() || file.isEmpty() || '/' in file || '\\' in file || file == "." || file == ".." || file.length > 1024 || console.length > 128) return null
        val code = params["code"]?.uppercase()?.filter { it.isLetterOrDigit() || it == '-' }?.take(16)?.ifBlank { null }
        return Request(console, file, code)
    }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
