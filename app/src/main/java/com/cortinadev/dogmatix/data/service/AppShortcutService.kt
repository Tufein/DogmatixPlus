package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.cortinadev.dogmatix.MainActivity
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.AppSettings
import com.cortinadev.dogmatix.data.local.dao.ConsoleDao
import com.cortinadev.dogmatix.data.state.PendingLibraryFilters
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.util.AppShortcut
import com.cortinadev.dogmatix.util.AppShortcuts
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.LibraryViews
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android app shortcuts for Dogmatix+: Downloads, each saved view and each console. Launchers show
 * them on a long press of the icon, and frontends that read app shortcuts (Cocoon picks them up in
 * *Add Games*) turn them into tiles that open Dogmatix+ on that console or view. They follow the
 * consoles and views as those change. [shortcutFor] builds one on request, for the "add a
 * shortcut" picker a launcher or frontend opens.
 */
@Singleton
class AppShortcutService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val consoleDao: ConsoleDao,
    private val appSettings: AppSettings
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun start() {
        scope.launch {
            combine(consoleDao.getAllConsoles(), appSettings.libraryViews) { consoles, views ->
                AppShortcuts.plan(
                    downloads(),
                    consoles.map { it.id to ConsoleFormatter.getConsoleDisplayName(it.id) },
                    LibraryViews.fromJson(views),
                    ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).takeIf { it > 0 } ?: AppShortcuts.MAX_DYNAMIC
                )
            }.distinctUntilChanged().debounce(2_000).collect { plan ->
                runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, plan.mapIndexed { i, s -> build(s, rank = i) }) }
            }
        }
    }

    /** Everything that can become a shortcut, for the picker: Downloads, saved views, consoles. */
    suspend fun available(): List<AppShortcut> {
        val consoles = consoleDao.getAllConsoles().first()
        val views = LibraryViews.fromJson(appSettings.libraryViews.first())
        return AppShortcuts.plan(downloads(), consoles.map { it.id to ConsoleFormatter.getConsoleDisplayName(it.id) }, views, max = Int.MAX_VALUE)
    }

    fun shortcutFor(shortcut: AppShortcut): ShortcutInfoCompat = build(shortcut, rank = 0)

    private fun downloads() = AppShortcuts.downloads(context.getString(R.string.nav_downloads), NavRoutes.Downloads.route)

    private fun build(s: AppShortcut, rank: Int): ShortcutInfoCompat {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            s.deepLink?.let { data = Uri.parse(it) }
            s.route?.let { putExtra(PendingLibraryFilters.EXTRA_OPEN_ROUTE, it) }
        }
        val icon = if (s.route != null) R.drawable.ic_shortcut_downloads else R.drawable.ic_shortcut_library
        return ShortcutInfoCompat.Builder(context, s.id)
            .setShortLabel(s.label.take(24))
            .setLongLabel(s.label)
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(intent)
            .setRank(rank)
            .apply { s.consoleId?.let { setCategories(setOf("com.cortinadev.dogmatix.console.$it")) } }
            .build()
    }
}
