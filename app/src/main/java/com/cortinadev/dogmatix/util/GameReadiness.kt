package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.service.GameLaunch

object GameReadiness {
    data class EmulatorState(val available: Boolean, val needsExtract: Boolean, val needsChoice: Boolean)

    /** Only a currently resolved preference can make an offline report ready to play. */
    fun emulatorState(games: List<GameLaunch>, gameOverride: String?, consoleDefault: String?): EmulatorState {
        val effective = games.mapNotNull { game ->
            val resolved = GameEmulatorOverrides.resolve(gameOverride, consoleDefault, game.handlers, { it.key }, { it.packageName })
            if (resolved.missingGameOverride) return@mapNotNull null
            val handler = resolved.handler ?: return@mapNotNull null
            val emulator = handler.emulatorId?.let(EmulatorCatalog::byId)
            val needsExtract = emulator != null && game.system != null && PlayRecipe.needsExtract(emulator, game.system, game.name)
            needsExtract
        }
        return EmulatorState(effective.isNotEmpty(), effective.isNotEmpty() && effective.all { it },
            effective.isEmpty() && games.any { it.handlers.isNotEmpty() })
    }
    /** Paths are relative to one read-only package/root; references stay in descendants. */
    fun referencesPresentAt(path: String, text: String, paths: Collection<String>): Boolean {
        if (!SheetParser.isSheet(path.substringAfterLast('/'))) return true
        val refs = when (path.substringAfterLast('.').lowercase()) {
            "cue" -> SheetParser.cueFiles(text)
            "gdi" -> SheetParser.gdiFiles(text)
            else -> SheetParser.m3uFiles(text)
        }
        val keys = paths.filter(GameArtifacts::safeLaunchPath).groupBy(ArchivePlan::portableKey)
        val parent = path.substringBeforeLast('/', "")
        return refs.isNotEmpty() && refs.all { ref ->
            val relative = ref.replace('\\', '/').removePrefix("./")
            val resolved = if (parent.isEmpty()) relative else "$parent/$relative"
            GameArtifacts.safeLaunchPath(relative) && GameArtifacts.safeLaunchPath(resolved) &&
                keys[ArchivePlan.portableKey(resolved)]?.size == 1
        }
    }

    fun referencesPresent(name: String, text: String, siblings: Collection<String>): Boolean {
        if (!SheetParser.isSheet(name)) return true
        val refs = when (name.substringAfterLast('.').lowercase()) {
            "cue" -> SheetParser.cueFiles(text)
            "gdi" -> SheetParser.gdiFiles(text)
            else -> SheetParser.m3uFiles(text)
        }
        val names = siblings.map { it.lowercase() }.toSet()
        return refs.isNotEmpty() && refs.all { GameArtifacts.safeLaunchReference(it) && it.lowercase() in names }
    }
}
