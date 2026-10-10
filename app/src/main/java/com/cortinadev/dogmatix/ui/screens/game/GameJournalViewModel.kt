package com.cortinadev.dogmatix.ui.screens.game

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.service.GameJournalService
import com.cortinadev.dogmatix.data.service.ProfileService
import com.cortinadev.dogmatix.util.JournalAttachment
import com.cortinadev.dogmatix.util.JournalAttachmentKind
import com.cortinadev.dogmatix.util.JournalEntry
import com.cortinadev.dogmatix.util.JournalKey
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GameJournalUi(val entry: JournalEntry? = null, val busy: Boolean = false, val failed: Boolean = false)
data class JournalPick(val key: JournalKey, val modifiedAt: Long, val kind: JournalAttachmentKind, val replaceId: String? = null)

@HiltViewModel
class GameJournalViewModel @Inject constructor(
    private val service: GameJournalService,
    profiles: ProfileService
) : ViewModel() {
    val activeProfile = profiles.activeId
    private val _ui = MutableStateFlow(GameJournalUi())
    val ui = _ui.asStateFlow()
    private var current: JournalKey? = null
    private var loadJob: Job? = null

    fun show(key: JournalKey) {
        if (current == key && _ui.value.entry != null) return
        current = key
        loadJob?.cancel()
        _ui.value = GameJournalUi(busy = true)
        loadJob = viewModelScope.launch { load(key) }
    }

    fun reload() { current?.let { key -> loadJob?.cancel(); loadJob = viewModelScope.launch { load(key) } } }

    private suspend fun load(key: JournalKey) {
        try {
            val entry = service.entry(key)
            if (current == key) _ui.value = GameJournalUi(entry)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (current == key) _ui.value = GameJournalUi(failed = true) }
    }

    fun accessible(attachment: JournalAttachment) = service.accessible(attachment)
    fun picker(kind: JournalAttachmentKind, replacing: JournalAttachment? = null): JournalPick? = _ui.value.entry
        ?.takeUnless { _ui.value.busy }?.let { JournalPick(it.key, it.modifiedAt, kind, replacing?.id) }

    fun attach(pick: JournalPick, uri: Uri) = act(pick.key) {
        service.attach(pick.key, pick.modifiedAt, uri, pick.kind, pick.replaceId) { current == pick.key }
    }

    fun save(note: String) { val entry = _ui.value.entry ?: return; act(entry.key) {
        service.saveNote(entry.key, entry.modifiedAt, note) { current == entry.key }
    } }

    fun remove(id: String) { val entry = _ui.value.entry ?: return; act(entry.key) {
        service.remove(entry.key, entry.modifiedAt, id) { current == entry.key }
    } }

    private fun act(key: JournalKey, block: suspend () -> Unit) {
        if (current != key || _ui.value.busy) return
        _ui.value = _ui.value.copy(busy = true, failed = false)
        viewModelScope.launch {
            try { block(); if (current == key) load(key) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (current == key) _ui.value = _ui.value.copy(busy = false, failed = true) }
        }
    }
}
