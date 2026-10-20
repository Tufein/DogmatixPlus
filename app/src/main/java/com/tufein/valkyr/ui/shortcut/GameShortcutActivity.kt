package com.tufein.valkyr.ui.shortcut

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.tufein.valkyr.MainActivity
import com.tufein.valkyr.R
import com.tufein.valkyr.data.service.GameShortcutService
import com.tufein.valkyr.data.service.GameShortcutService.StartResult
import com.tufein.valkyr.util.GameShortcuts
import com.tufein.valkyr.util.ToastUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 3.5: what a game shortcut (a home-screen icon, or a recently played game in the app icon's
 * menu) opens. It has no screen of its own: it starts the game in its emulator and closes, so the
 * emulator returns to the home screen. When the game cannot start without a question or an
 * explanation, the game's page opens in Dogmatix+ instead ([PendingGameShortcut]).
 *
 * Not exported: launchers start pinned and dynamic shortcuts on behalf of this app
 * (LauncherApps), so no other app can send it a game. The intent only names a library row
 * ([GameShortcuts.Key]), which [GameShortcutService.start] looks up again.
 */
@AndroidEntryPoint
class GameShortcutActivity : ComponentActivity() {

    @Inject lateinit var games: GameShortcutService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = GameShortcuts.fromIntent(intent?.action, intent?.getStringExtra(GameShortcuts.EXTRA_GAME))
        if (key == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            when (games.start(this@GameShortcutActivity, key)) {
                StartResult.STARTED -> Unit
                // The page says the game is not in the library.
                StartResult.MISSING -> openPage(key, play = false)
                StartResult.NO_FILES -> {
                    ToastUtil.showError(applicationContext, getString(R.string.pin35_no_files))
                    openPage(key, play = false)
                }
                StartResult.PLAY_ON_PAGE -> openPage(key, play = true)
            }
            finish()
        }
    }

    /** Dogmatix+ on the game's page; with [play], its Play runs at once (the chooser, or why it cannot start). */
    private fun openPage(key: GameShortcuts.Key, play: Boolean) {
        PendingGameShortcut.submit(key.consoleId, key.fileName, play)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * A game page a shortcut asked for, waiting for the app to show it. Only [GameShortcutActivity]
 * (in this process) submits one, so nothing from outside can make the page press Play.
 */
object PendingGameShortcut {
    data class Request(val consoleId: String, val fileName: String, val play: Boolean, val at: Long)

    private val _request = MutableStateFlow<Request?>(null)
    /** A page still to open. */
    val request: StateFlow<Request?> = _request.asStateFlow()

    private val _play = MutableStateFlow<Request?>(null)
    /** A page that should press Play once it shows the game on the device. */
    val play: StateFlow<Request?> = _play.asStateFlow()

    /** Requests older than this are dropped (the app was not opened, or the game never showed). */
    private const val MAX_AGE_MS = 60_000L

    fun submit(consoleId: String, fileName: String, play: Boolean) {
        val request = Request(consoleId, fileName, play, System.currentTimeMillis())
        _play.value = if (play) request else null
        _request.value = request
    }

    /** The page to open now, once. */
    fun consumeOpen(): Request? = _request.value.also { _request.value = null }?.takeIf { fresh(it) }

    /** True once when the page of [consoleId] / [fileName] should press Play. */
    fun consumePlay(consoleId: String, fileName: String): Boolean {
        val request = _play.value ?: return false
        if (!fresh(request)) { _play.value = null; return false }
        if (request.consoleId != consoleId || request.fileName != fileName) return false
        _play.value = null
        return true
    }

    private fun fresh(request: Request) = System.currentTimeMillis() - request.at in 0..MAX_AGE_MS
}
