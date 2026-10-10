package com.cortinadev.dogmatix.ui.screens.sources

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.repository.SourcesRepository
import com.cortinadev.dogmatix.data.service.SourceScanService
import com.cortinadev.dogmatix.util.BoundedStreams
import com.cortinadev.dogmatix.util.RomsetCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class RomsetCatalogState(
    val catalog: RomsetCatalog.Catalog? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val failed: Boolean = false,
    val added: Int? = null
)

@HiltViewModel
class RomsetCatalogViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sources: SourcesRepository,
    private val scanner: SourceScanService
) : ViewModel() {
    private val mutableState = MutableStateFlow(RomsetCatalogState())
    val state = mutableState.asStateFlow()
    private val adding = AtomicBoolean(false)
    val known = sources.manufacturers.map { makers -> makers.flatMap { maker ->
        maker.consoles.flatMap { console -> console.urls.map { console.id to it.url.trimEnd('/') } }
    }.toSet() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    init { reload() }

    fun reload() {
        if (adding.get()) return
        viewModelScope.launch {
            mutableState.value = RomsetCatalogState()
            try {
                val catalog = withContext(Dispatchers.IO) {
                    context.assets.open(RomsetCatalog.ASSET).use {
                        RomsetCatalog.parse(BoundedStreams.read(it, RomsetCatalog.MAX_BYTES + 1).toString(Charsets.UTF_8))
                    }
                }
                mutableState.value = RomsetCatalogState(catalog, loading = false)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.value = RomsetCatalogState(loading = false, failed = true) }
        }
    }

    fun add(ids: Set<String>) {
        val catalog = state.value.catalog ?: return
        val choices = catalog.entries.filter { it.id in ids }
        if (choices.isEmpty() || choices.size != ids.size || !adding.compareAndSet(false, true)) return
        mutableState.value = state.value.copy(busy = true, failed = false, added = null)
        viewModelScope.launch {
            try {
                val added = sources.mergeRomsets(choices)
                mutableState.value = state.value.copy(busy = false, added = added.size)
                scanner.scanSelectedUrls(added)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.value = state.value.copy(busy = false, failed = true) }
            finally { adding.set(false); mutableState.value = state.value.copy(busy = false) }
        }
    }
}
