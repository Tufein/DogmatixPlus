package com.cortinadev.dogmatix.data.service

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.util.GameRemoval
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class GameHandler(val component: String, val label: String)
data class GameLaunch(val uri: String, val name: String, val handlers: List<GameHandler>)

@Singleton
class GameLaunchService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val library: LibraryIndexService
) {
    private val preferences = context.getSharedPreferences("game_launchers", Context.MODE_PRIVATE)
    private fun intent(uri: String) = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "application/octet-stream")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("Game", Uri.parse(uri)) }
    suspend fun choices(file: DownloadableFileEntity): List<GameLaunch> = withContext(Dispatchers.IO) {
        val artifacts = library.removalPlan(file).filter { GameRemoval.safeReference(it.name) }
        val playable = artifacts.filter { it.name.substringAfterLast('.').lowercase() !in setOf("zip", "7z", "rar", "bin", "img") }
            .ifEmpty { artifacts }
        playable.map { artifact ->
            @Suppress("DEPRECATION")
            val activities = context.packageManager.queryIntentActivities(intent(artifact.uri), PackageManager.MATCH_DEFAULT_ONLY)
            GameLaunch(artifact.uri, artifact.name, activities.filter { it.activityInfo.packageName != context.packageName }.map {
                GameHandler(ComponentName(it.activityInfo.packageName, it.activityInfo.name).flattenToString(), it.loadLabel(context.packageManager).toString())
            }.distinctBy { it.component })
        }
    }
    fun preferred(consoleId: String) = preferences.getString(consoleId, null)
    fun clear(consoleId: String) { preferences.edit().remove(consoleId).apply() }
    fun launch(context: Context, consoleId: String, game: GameLaunch, handler: GameHandler, remember: Boolean) {
        check(game.handlers.any { it.component == handler.component })
        val component = ComponentName.unflattenFromString(handler.component) ?: error("Application unavailable")
        context.startActivity(intent(game.uri).setComponent(component))
        if (remember) preferences.edit().putString(consoleId, handler.component).apply()
    }
}
