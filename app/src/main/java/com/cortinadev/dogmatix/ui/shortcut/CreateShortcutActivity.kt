package com.cortinadev.dogmatix.ui.shortcut

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutManagerCompat
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.AppShortcutService
import com.cortinadev.dogmatix.util.AppShortcut
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The "create shortcut" picker (`ACTION_CREATE_SHORTCUT`): a launcher or a frontend such as Cocoon
 * opens it when the user adds a Dogmatix+ shortcut, and gets back the chosen console, saved view or
 * the Downloads section.
 */
@AndroidEntryPoint
class CreateShortcutActivity : ComponentActivity() {

    @Inject lateinit var shortcuts: AppShortcutService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var options by remember { mutableStateOf<List<AppShortcut>?>(null) }
                    LaunchedEffect(Unit) { options = runCatching { shortcuts.available() }.getOrDefault(emptyList()) }
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.shortcut_pick_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
                        LazyColumn {
                            items(options.orEmpty(), key = { it.id }) { option ->
                                Text(
                                    option.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.fillMaxWidth().clickable { choose(option) }.padding(vertical = 14.dp, horizontal = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun choose(option: AppShortcut) {
        setResult(Activity.RESULT_OK, ShortcutManagerCompat.createShortcutResultIntent(this, shortcuts.shortcutFor(option)))
        finish()
    }
}
