package com.cortinadev.dogmatix.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.entity.ConsoleEntity
import com.cortinadev.dogmatix.data.repository.ConsoleRepository
import com.cortinadev.dogmatix.data.repository.WishlistRepository
import com.cortinadev.dogmatix.data.repository.WishlistStatus
import com.cortinadev.dogmatix.data.state.LibraryFilterRequest
import com.cortinadev.dogmatix.data.state.PendingLibraryFilters
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.Stepper
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.ui.screens.settings.PillButton
import com.cortinadev.dogmatix.util.ConsoleFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WishlistUiState(val loading: Boolean = true, val items: List<WishlistStatus> = emptyList(), val consoles: List<ConsoleEntity> = emptyList())

@HiltViewModel
class WishlistViewModel @Inject constructor(
    private val wishlist: WishlistRepository,
    consoleRepository: ConsoleRepository,
    private val pendingFilters: PendingLibraryFilters
) : ViewModel() {
    private val consolesFlow = consoleRepository.getAllConsoles()
    private val _ui = MutableStateFlow(WishlistUiState())
    val ui: StateFlow<WishlistUiState> = _ui.asStateFlow()

    init {
        // Re-read whenever the list changes; the match counts come from the library table.
        viewModelScope.launch { wishlist.items.collect { reload() } }
    }

    private suspend fun reload() {
        val consoles = consolesFlow.first().sortedBy { ConsoleFormatter.getConsoleDisplayName(it.id) }
        _ui.value = WishlistUiState(false, wishlist.statuses(), consoles)
    }

    /** True when added; false for an empty or duplicate entry. */
    suspend fun add(title: String, consoleId: String?): Boolean = wishlist.add(title, consoleId)

    fun remove(id: Long) { viewModelScope.launch { wishlist.remove(id) } }

    /** Opens the library with this title already searched. */
    fun show(status: WishlistStatus) {
        pendingFilters.submit(LibraryFilterRequest(consoles = setOfNotNull(status.item.consoleId), query = status.item.title))
    }
}

/** Games the user wants: add a title (optionally for one console), see which ones have turned up. */
@Composable
fun WishlistScreen(navController: NavController, viewModel: WishlistViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current
    var showAdd by remember { mutableStateOf(false) }
    // Android 13+ asks before the "a wanted game turned up" notification may be shown.
    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }
    if (showAdd) {
        AddWishDialog(ui.consoles, onAdd = { title, console ->
            viewModel.viewModelScopeAdd(context, title, console)
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }, onDismiss = { showAdd = false })
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 12.dp)) {
        ToolsTitle(stringResource(R.string.nav_wishlist))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "add") {
                ToolRow(
                    stringResource(R.string.wishlist_add),
                    listOf(if (ui.loading) stringResource(R.string.tools_scanning) else if (ui.items.isEmpty()) stringResource(R.string.wishlist_empty) else stringResource(R.string.wishlist_hint)),
                    { showAdd = true }, Modifier.focusRequester(firstFocus)
                ) { PillButton(stringResource(R.string.wishlist_add_action)) { showAdd = true } }
            }
            items(ui.items, key = { it.item.id }) { status ->
                val found = status.matches > 0
                val console = status.item.consoleId?.let { ConsoleFormatter.getConsoleDisplayName(it) } ?: stringResource(R.string.wishlist_any_console)
                ToolRow(
                    status.item.title,
                    listOf(console, if (found) pluralStringResource(R.plurals.wishlist_found, status.matches, status.matches) else stringResource(R.string.wishlist_not_yet)),
                    onClick = { if (found) { viewModel.show(status) } else viewModel.remove(status.item.id) },
                    badge = if (found) ({ Badge(stringResource(R.string.wishlist_badge_found), warning = false) }) else null
                ) {
                    if (found) PillButton(stringResource(R.string.wishlist_show)) { viewModel.show(status) }
                    PillButton(stringResource(R.string.wishlist_remove)) { viewModel.remove(status.item.id) }
                }
            }
        }
    }
}

private fun WishlistViewModel.viewModelScopeAdd(context: android.content.Context, title: String, consoleId: String?) {
    val app = context.applicationContext
    viewModelScope.launch {
        if (add(title, consoleId)) com.cortinadev.dogmatix.util.ToastUtil.showSuccess(app, app.getString(R.string.wishlist_added, title.trim()))
        else com.cortinadev.dogmatix.util.ToastUtil.showInfo(app, app.getString(R.string.wishlist_exists))
    }
}

@Composable
private fun AddWishDialog(consoles: List<ConsoleEntity>, onAdd: (String, String?) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    // 0 = any console, 1..n = the consoles in order.
    var index by remember { mutableStateOf(0) }
    fun step(delta: Int) { index = ((index + delta) % (consoles.size + 1) + consoles.size + 1) % (consoles.size + 1) }
    val fieldFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wishlist_add_title)) },
        text = {
            Column {
                Text(stringResource(R.string.wishlist_add_hint))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, singleLine = true,
                    label = { Text(stringResource(R.string.wishlist_title_label)) },
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus)
                )
                Spacer(Modifier.height(12.dp))
                Stepper(
                    value = if (index == 0) stringResource(R.string.wishlist_any_console) else ConsoleFormatter.getConsoleDisplayName(consoles[index - 1].id),
                    onDecrement = { step(-1) }, onIncrement = { step(1) }, valueWidth = 180.dp
                )
            }
        },
        confirmButton = {
            DialogButton(
                text = stringResource(R.string.wishlist_add_action), enabled = title.trim().length >= 2,
                onClick = { onAdd(title.trim(), if (index == 0) null else consoles[index - 1].id); onDismiss() }
            )
        },
        dismissButton = { DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss) }
    )
}
