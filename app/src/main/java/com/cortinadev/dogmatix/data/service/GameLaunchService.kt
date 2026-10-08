package com.cortinadev.dogmatix.data.service

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.FileProvider
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.util.ApkAssets
import com.cortinadev.dogmatix.util.DiskScanner
import com.cortinadev.dogmatix.util.EmulatorCatalog
import com.cortinadev.dogmatix.util.FileRef
import com.cortinadev.dogmatix.util.GameLaunchKeys
import com.cortinadev.dogmatix.util.GameArtifacts
import com.cortinadev.dogmatix.util.PlayRecipe
import com.cortinadev.dogmatix.util.PlaySystem
import com.cortinadev.dogmatix.util.PlayTarget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GameLaunchService"

/**
 * One app Play can start a game with. [key] is what is remembered for the console
 * ([GameLaunchKeys]): a component for an app found through `ACTION_VIEW`, a catalogue key for a
 * known emulator ([emulatorId], with the RetroArch [core]) that is started with its own recipe.
 */
data class GameHandler(
    val key: String,
    val label: String,
    val packageName: String,
    val emulatorId: String? = null,
    val core: String? = null
)

/**
 * One file of a game that can be started. [siblings] are the document URIs of the game's other
 * files in the same folder (a cue sheet's tracks, an m3u's discs): every launch grants them too.
 */
data class GameLaunch(
    val uri: String,
    val name: String,
    val handlers: List<GameHandler>,
    val file: FileRef = FileRef(name, uri, null),
    val siblings: List<String> = emptyList(),
    val system: PlaySystem? = null
)

/** How a start went, so the game page can say what to do. */
enum class LaunchOutcome {
    STARTED,

    /** The emulator does not open this archive for this console: extract the game first. */
    NEEDS_EXTRACT,

    /** No intent of the handler found an activity that would take it. */
    FAILED
}

/**
 * Play: the apps that can start a downloaded game, and the start itself. Known emulators of the
 * catalogue ([EmulatorCatalog]) come first and get the launch recipe ES-DE uses for them (explicit
 * activity, action, extras); after them every other app that takes the file through
 * `ACTION_VIEW`, one entry per package. The pick can be remembered per console.
 *
 * None of the recipes could be run against real emulators where this was written.
 */
@Singleton
class GameLaunchService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val library: LibraryIndexService,
    private val actionLog: ActionLogService,
    private val profiles: ProfileService
) {
    private val preferences = context.getSharedPreferences("game_launchers", Context.MODE_PRIVATE)

    suspend fun choices(file: DownloadableFileEntity): List<GameLaunch> = withContext(Dispatchers.IO) {
        val artifacts = library.launchPlan(file).filter { GameArtifacts.safeLaunchReference(it.name) }
        val playable = artifacts.groupBy { it.parent }.values.flatMap { folder ->
            val entries = PlayRecipe.entryFiles(folder.map { it.name }).toSet()
            folder.filter { it.name in entries }
        }.sortedBy { PlayRecipe.entryRank(it.name) }
        val system = EmulatorCatalog.systemOf(file.consoleId)
        val targets = system?.let { EmulatorCatalog.targetsFor(it, installedPackages()) }.orEmpty()
        val root = externalRoot()
        playable.map { artifact ->
            val ref = refOf(artifact)
            val siblings = artifacts.filter { it.uri != artifact.uri && it.parent == artifact.parent }.map { it.uri }
            val catalogue = targets.mapNotNull { target ->
                val emulator = EmulatorCatalog.byId(target.emulatorId) ?: return@mapNotNull null
                val variant = emulator.variants.firstOrNull { it.packageName == target.packageName } ?: return@mapNotNull null
                val built = PlayRecipe.build(emulator, variant, target.core, ref, root, siblings)
                // The recipe wants a path this library location does not give: the app's own
                // ACTION_VIEW entry (below) is all that is left, as for any other app.
                if (emulator.template != null && !built.usesTemplate) return@mapNotNull null
                GameHandler(target.key, target.label, target.packageName, target.emulatorId, target.core)
            }
            @Suppress("DEPRECATION")
            val activities = context.packageManager.queryIntentActivities(GameLaunchIntents.view(artifact.uri, artifact.name), PackageManager.MATCH_DEFAULT_ONLY)
            val generic = activities.filter { it.activityInfo.packageName != context.packageName }.map {
                GameHandler(ComponentName(it.activityInfo.packageName, it.activityInfo.name).flattenToString(), it.loadLabel(context.packageManager).toString(), it.activityInfo.packageName)
            }
            GameLaunch(artifact.uri, artifact.name, GameLaunchKeys.merge(catalogue, generic) { it.packageName }, ref, siblings, system)
        }
    }

    private fun preferenceKey(consoleId: String) = com.cortinadev.dogmatix.data.local.PersonalPreferences.prefix(profiles.activeId.value) + consoleId
    fun preferred(consoleId: String): String? = preferences.getString(preferenceKey(consoleId), null)
    fun clear(consoleId: String) { preferences.edit().remove(preferenceKey(consoleId)).apply() }

    /** Stores [key] ([GameLaunchKeys]) for [consoleId]; null forgets it (Play asks again). */
    fun setPreferred(consoleId: String, key: String?) {
        if (key.isNullOrBlank()) clear(consoleId) else preferences.edit().putString(preferenceKey(consoleId), key).apply()
    }

    /** The handler of [game] the remembered [stored] value means, or null when it names nothing installed now. */
    fun resolve(game: GameLaunch, stored: String?): GameHandler? =
        GameLaunchKeys.resolve(stored, game.handlers, { it.key }, { it.packageName })

    /** The catalogue emulators installed for each of [consoleIds] (RetroArch once per core); consoles without any are left out. */
    suspend fun catalogueTargets(consoleIds: Collection<String>): Map<String, List<PlayTarget>> = withContext(Dispatchers.IO) {
        val installed = installedPackages()
        consoleIds.associateWith { id -> EmulatorCatalog.systemOf(id)?.let { EmulatorCatalog.targetsFor(it, installed) }.orEmpty() }
            .filterValues { it.isNotEmpty() }
    }

    /** A readable name for a stored value: the emulator (and core), or the app of a component; null when unknown. */
    fun labelOf(stored: String?): String? {
        GameLaunchKeys.parseCatalogue(stored)?.let { (id, core) ->
            val emulator = EmulatorCatalog.byId(id) ?: return null
            val packageName = GameLaunchKeys.cataloguePackage(stored)
            val variant = emulator.variants.firstOrNull { it.packageName == packageName }
            val label = variant?.let { EmulatorCatalog.variantLabel(emulator, it) } ?: emulator.label
            return if (core != null) "$label (${EmulatorCatalog.coreLabel(core)})" else label
        }
        val pkg = GameLaunchKeys.componentPackage(stored) ?: return null
        return try {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) { null }
    }

    /** Which catalogue packages are installed (the manifest `<queries>` must list them, or Android 11+ hides them). */
    private fun installedPackages(): Set<String> = EmulatorCatalog.allPackages().filterTo(HashSet()) { ApkAssets.isInstalled(context, it) }

    /**
     * Starts [game] with [handler]. A catalogue emulator gets its recipe's intents in order until
     * one finds an activity; any other app the plain `ACTION_VIEW`. When it started and [remember]
     * is set, the handler (or [GameLaunchKeys.AUTOMATIC] when [automatic]) is kept for the console.
     */
    fun launch(context: Context, consoleId: String, game: GameLaunch, handler: GameHandler, remember: Boolean, automatic: Boolean = false): LaunchOutcome {
        check(game.handlers.any { it.key == handler.key })
        val outcome = if (handler.emulatorId != null) launchCatalogue(context, game, handler) else {
            val component = ComponentName.unflattenFromString(handler.key) ?: error("Application unavailable")
            if (start(context, GameLaunchIntents.view(game.uri, game.name, game.siblings).setComponent(component))) LaunchOutcome.STARTED else LaunchOutcome.FAILED
        }
        if (outcome == LaunchOutcome.STARTED) actionLog.played(consoleId, game.name)
        if (outcome == LaunchOutcome.STARTED && remember) setPreferred(consoleId, if (automatic) GameLaunchKeys.AUTOMATIC else handler.key)
        return outcome
    }

    private fun launchCatalogue(context: Context, game: GameLaunch, handler: GameHandler): LaunchOutcome {
        val emulator = EmulatorCatalog.byId(handler.emulatorId ?: return LaunchOutcome.FAILED) ?: return LaunchOutcome.FAILED
        val variant = emulator.variants.firstOrNull { it.packageName == handler.packageName } ?: return LaunchOutcome.FAILED
        val system = game.system
        if (system != null && PlayRecipe.needsExtract(emulator, system, game.name)) return LaunchOutcome.NEEDS_EXTRACT
        val built = PlayRecipe.build(emulator, variant, handler.core, game.file, externalRoot(), game.siblings)
        return if (built.attempts.any { start(context, GameLaunchIntents.fromSpec(it)) }) LaunchOutcome.STARTED else LaunchOutcome.FAILED
    }

    /** False when no activity takes [intent] or the app refuses it (not exported, a permission). */
    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Launch refused: ${e.message}")
        false
    }

    /** Where [artifact] is: its document URI, the path its document id spells, and a FileProvider URI when this app can read that path. */
    private fun refOf(artifact: RemovalFile): FileRef {
        val uri = Uri.parse(artifact.uri)
        val path = try {
            DiskScanner.canonicalPath(uri.authority, DocumentsContract.getDocumentId(uri))
        } catch (_: IllegalArgumentException) { null }
        // A FileProvider URI only helps when this app can read the file itself (it usually cannot).
        val provider = path?.takeIf { File(it).canRead() }?.let {
            try { FileProvider.getUriForFile(context, "${context.packageName}.provider", File(it)).toString() } catch (_: IllegalArgumentException) { null }
        }
        return FileRef(artifact.name, artifact.uri, path, provider)
    }

    private fun externalRoot(): String =
        Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"
}
