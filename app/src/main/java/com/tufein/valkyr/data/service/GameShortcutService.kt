package com.tufein.valkyr.data.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import com.tufein.valkyr.R
import com.tufein.valkyr.data.local.AppSettings
import com.tufein.valkyr.data.local.dao.DownloadableFileDao
import com.tufein.valkyr.data.local.entity.DownloadableFileEntity
import com.tufein.valkyr.data.repository.DownloadableFileRepository
import com.tufein.valkyr.ui.shortcut.GameShortcutActivity
import com.tufein.valkyr.util.GameShortcuts
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GameShortcuts"

/**
 * 3.5 "Games on your home screen": game shortcuts that start a library game straight in its
 * emulator. [pin] asks the launcher to put one on the home screen (from the game page); [info]
 * builds one, also for the recently played games in the app icon's menu ([AppShortcutService]);
 * [start] is what a tap does ([GameShortcutActivity]); [disable] / [enable] follow the game when
 * it is removed and restored.
 *
 * The pinning and shortcut calls follow the platform documentation (ShortcutManager,
 * LauncherApps); they could not be tried on a handheld's launcher where this was written.
 */
@Singleton
class GameShortcutService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: DownloadableFileRepository,
    private val fileDao: DownloadableFileDao,
    private val launcher: GameLaunchService,
    private val profiles: ProfileService,
    private val appSettings: AppSettings,
    private val covers: dagger.Lazy<CoverRepository>,
    private val imageLoader: dagger.Lazy<ImageLoader>
) {
    enum class PinResult {
        /** The launcher was asked; it adds the icon or asks the user itself. */
        REQUESTED,
        /** The launcher cannot pin shortcuts. */
        UNSUPPORTED,
        /** No installed app can open the game, so the icon would not start anything. */
        NO_EMULATOR,
        FAILED
    }

    enum class StartResult {
        /** The emulator was started. */
        STARTED,
        /** The game is not in the library (any more), or the active profile hides it. */
        MISSING,
        /** The library has the game, but its files are not on the device. */
        NO_FILES,
        /** Play has to ask or explain (no emulator remembered, it did not start): the game page takes over. */
        PLAY_ON_PAGE
    }

    /** One game the icon menu may show: what was started, and the library rows it belongs to (best first). */
    data class RecentGame(val play: GameShortcuts.RecentPlay, val rows: List<DownloadableFileEntity>)

    /** Asks the launcher to pin [file] to the home screen. Only for a game Play could start (on the device). */
    suspend fun pin(file: DownloadableFileEntity): PinResult = withContext(Dispatchers.IO) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return@withContext PinResult.UNSUPPORTED
        val key = GameShortcuts.key(file.consoleId, file.fileName) ?: return@withContext PinResult.FAILED
        // The Play button's own check: some installed app must take the game's files.
        if (launcher.choices(file).none { it.handlers.isNotEmpty() }) return@withContext PinResult.NO_EMULATOR
        val shortcut = info(key, labelOf(file))
        // A shortcut disabled when the game was removed must be enabled before it can be pinned again.
        runCatching { ShortcutManagerCompat.enableShortcuts(context, listOf(shortcut)) }
        try {
            if (ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)) PinResult.REQUESTED else PinResult.FAILED
        } catch (e: IllegalStateException) {
            // The platform refuses requests from an app that is not in the foreground.
            Log.w(TAG, "Pin refused: ${e.message}")
            PinResult.FAILED
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Pin refused: ${e.message}")
            PinResult.FAILED
        }
    }

    /** The clean title a shortcut of [file] shows. */
    fun labelOf(file: DownloadableFileEntity): String = GameShortcuts.label(file.name.ifBlank { file.fileName })

    /**
     * The shortcut of [key]: its title, the game's cover when it is already cached on the device
     * (else the app icon), its console as category (frontends file it there), and the intent that
     * starts it through [GameShortcutActivity]. Loads the cover, so call it off the main thread.
     */
    suspend fun info(key: GameShortcuts.Key, label: String, rank: Int = 0): ShortcutInfoCompat {
        val icon = coverIcon(key) ?: IconCompat.createWithResource(context, R.drawable.ic_shortcut_library)
        return ShortcutInfoCompat.Builder(context, key.shortcutId)
            .setShortLabel(label.take(24))
            .setLongLabel(label)
            .setIcon(icon)
            .setIntent(intent(key))
            .setRank(rank)
            .setCategories(setOf("com.tufein.valkyr.console.${key.consoleId}"))
            .build()
    }

    /** Only the library key travels; [GameShortcutActivity] looks it up again. */
    private fun intent(key: GameShortcuts.Key): Intent = Intent(context, GameShortcutActivity::class.java).apply {
        action = GameShortcuts.ACTION_PLAY
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
        putExtra(GameShortcuts.EXTRA_GAME, key.value)
    }

    /**
     * The cover as an adaptive icon, only from the image cache (nothing is downloaded for it): the
     * square middle of the cover fills the icon's visible area, the rest of the layer repeats it
     * larger for launchers that move the icon around.
     */
    private suspend fun coverIcon(key: GameShortcuts.Key): IconCompat? = withContext(Dispatchers.IO) {
        try {
            val repo = covers.get()
            val url = repo.cached(key.consoleId, key.fileName)
                ?: withTimeoutOrNull(4_000) { repo.coverUrl(key.consoleId, key.fileName) }
                ?: return@withContext null
            val size = ShortcutManagerCompat.getIconMaxWidth(context).takeIf { it > 0 }?.coerceIn(96, 432) ?: 192
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(size)
                .scale(Scale.FILL)
                .allowHardware(false)
                .networkCachePolicy(CachePolicy.DISABLED)
                .build()
            val drawable = (imageLoader.get().execute(request) as? SuccessResult)?.drawable ?: return@withContext null
            IconCompat.createWithAdaptiveBitmap(adaptive(drawable.toBitmap(), size))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.w(TAG, "Cover icon unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun adaptive(source: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val (l, t, r, b) = GameShortcuts.squareCrop(source.width, source.height).toList()
        if (r <= l || b <= t) return out
        val crop = Rect(l, t, r, b)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(source, crop, Rect(0, 0, size, size), paint)
        // An adaptive icon is 108 units wide; launchers show the middle 72 of them.
        val inset = size / 6
        canvas.drawBitmap(source, crop, Rect(inset, inset, size - inset, size - inset), paint)
        return out
    }

    /**
     * A tap on a game shortcut: looks [key] up in the library for the active profile and starts it
     * the way the game page's Play does when it does not have to ask ([GameLaunchService.direct]).
     * Anything else (no emulator remembered, it did not start) is left to the game page.
     */
    suspend fun start(activity: Activity, key: GameShortcuts.Key): StartResult {
        // The emulator choices are per profile: wait until the app has read which profile is active.
        val profileId = appSettings.activeProfile.first()
        withTimeoutOrNull(2_000) { while (profiles.currentIdNow() != profileId) delay(20) }
            ?: return StartResult.PLAY_ON_PAGE
        return try {
            appSettings.withActiveProfile(profileId) {
                val item = repository.findByFileNames(listOf(key.fileName)) { key.consoleId }[key.fileName]
                    ?.takeIf { it.file.consoleId == key.consoleId && profiles.current().allows(it.file.consoleId, it.tags) }
                    ?: return@withActiveProfile StartResult.MISSING
                // What the library finds on the device for this row (no file from the intent).
                val choices = launcher.choices(item.file)
                if (choices.isEmpty()) return@withActiveProfile StartResult.NO_FILES
                val (game, handler) = launcher.direct(key.consoleId, key.fileName, choices)
                    ?: return@withActiveProfile StartResult.PLAY_ON_PAGE
                when (launcher.launch(activity, key.consoleId, game, handler, remember = false)) {
                    LaunchOutcome.STARTED -> {
                        runCatching { ShortcutManagerCompat.reportShortcutUsed(context, key.shortcutId) }
                        StartResult.STARTED
                    }
                    // The page tries again and then says why, with the chooser open.
                    else -> StartResult.PLAY_ON_PAGE
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.w(TAG, "Shortcut start failed: ${e.javaClass.simpleName}")
            StartResult.PLAY_ON_PAGE
        }
    }

    /**
     * The library games behind [plays] (newest first) that the active profile may see, each with
     * its library rows best first ([GameShortcuts.libraryMatches]); plays without a row are left out.
     */
    suspend fun recentGames(plays: List<GameShortcuts.RecentPlay>): List<RecentGame> = withContext(Dispatchers.IO) {
        if (plays.isEmpty()) return@withContext emptyList()
        val restrictions = profiles.current()
        val namesByConsole = HashMap<String, List<String>>()
        plays.mapNotNull { play ->
            val names = namesByConsole.getOrPut(play.consoleId) { runCatching { fileDao.fileNamesFor(play.consoleId) }.getOrDefault(emptyList()) }
            val matches = GameShortcuts.libraryMatches(play.consoleId, play.fileName, names).take(MAX_MATCHES)
            if (matches.isEmpty()) return@mapNotNull null
            val found = repository.findByFileNames(matches) { play.consoleId }
            val rows = matches.mapNotNull { name -> found[name]?.takeIf { it.file.consoleId == play.consoleId && restrictions.allows(it.file.consoleId, it.tags) }?.file }
            rows.takeIf { it.isNotEmpty() }?.let { RecentGame(play, it) }
        }
    }

    /** Disables the shortcuts of a game that left the device; a pinned icon then says why when tapped. */
    fun disable(consoleId: String, fileName: String) {
        runCatching {
            val ids = GameShortcuts.affected(gameShortcuts().map { it.id }, consoleId, fileName)
            if (ids.isNotEmpty()) ShortcutManagerCompat.disableShortcuts(context, ids, context.getString(R.string.pin35_disabled))
        }.onFailure { Log.w(TAG, "Could not disable a shortcut: ${it.javaClass.simpleName}") }
    }

    /** Enables the shortcuts of a game that is back on the device (restored from the trash). */
    fun enable(consoleId: String, fileName: String) {
        runCatching {
            val all = gameShortcuts()
            val ids = GameShortcuts.affected(all.map { it.id }, consoleId, fileName).toSet()
            val back = all.filter { it.id in ids }.distinctBy { it.id }
            if (back.isNotEmpty()) ShortcutManagerCompat.enableShortcuts(context, back)
        }.onFailure { Log.w(TAG, "Could not enable a shortcut: ${it.javaClass.simpleName}") }
    }

    private fun gameShortcuts(): List<ShortcutInfoCompat> =
        ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED or ShortcutManagerCompat.FLAG_MATCH_DYNAMIC)
            .filter { GameShortcuts.parseId(it.id) != null }

    private companion object {
        /** Library rows looked at per play (the same game from a few sources). */
        const val MAX_MATCHES = 8
    }
}
