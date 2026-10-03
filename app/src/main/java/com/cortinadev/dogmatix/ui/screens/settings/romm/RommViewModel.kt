package com.cortinadev.dogmatix.ui.screens.settings.romm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.entity.ConsoleEntity
import com.cortinadev.dogmatix.data.repository.ConsoleRepository
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.RommClient
import com.cortinadev.dogmatix.data.service.RommPlatform
import com.cortinadev.dogmatix.data.service.CoverRunState
import com.cortinadev.dogmatix.data.service.RommCoverService
import com.cortinadev.dogmatix.data.service.RommLibraryService
import com.cortinadev.dogmatix.data.service.RommLibraryState
import com.cortinadev.dogmatix.data.service.RommTrustService
import com.cortinadev.dogmatix.data.service.ServerCertificate
import com.cortinadev.dogmatix.util.CertTrust
import javax.net.ssl.SSLException
import com.cortinadev.dogmatix.ui.common.executeWithToast
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.RommPlatformMapper
import com.cortinadev.dogmatix.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RommUiState(
    val url: String = "",
    val token: String = "",
    val autoUpload: Boolean = false,
    val platformMap: Map<String, Int> = emptyMap(),
    val consoles: List<ConsoleEntity> = emptyList(),
    /** SHA-256 of the server certificate the user chose to trust; "" = none. */
    val trustFingerprint: String = "",
    val markGames: Boolean = true,
    val esdeDirectory: String = ""
)

/** A certificate the system does not trust, shown to the user to confirm (or not). */
data class TrustPrompt(val url: String, val certificate: ServerCertificate)

@HiltViewModel
class RommViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    consoleRepository: ConsoleRepository,
    private val client: RommClient,
    private val trustService: RommTrustService,
    private val libraryService: RommLibraryService,
    private val coverService: RommCoverService
) : ViewModel() {

    val uiState: StateFlow<RommUiState> = combine(
        combine(
            settingsRepository.rommUrl,
            settingsRepository.rommToken,
            settingsRepository.rommAutoUpload,
            settingsRepository.rommPlatformMap,
            consoleRepository.getAllConsoles().map { list -> list.sortedBy { ConsoleFormatter.getConsoleDisplayName(it.id) } }
        ) { url, token, auto, map, consoles -> RommUiState(url, token, auto, map, consoles) },
        combine(settingsRepository.rommTrustFingerprint, settingsRepository.rommMarkGames, settingsRepository.esdeDirectory) { fp, mark, esde -> Triple(fp, mark, esde) }
    ) { base, (fp, mark, esde) -> base.copy(trustFingerprint = fp, markGames = mark, esdeDirectory = esde) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RommUiState())

    /** What the server's game list looks like right now (for the "games on RomM" row). */
    val libraryState: StateFlow<RommLibraryState> = libraryService.state
    val coverState: StateFlow<CoverRunState> = coverService.state

    private val _trustPrompt = MutableStateFlow<TrustPrompt?>(null)
    val trustPrompt: StateFlow<TrustPrompt?> = _trustPrompt.asStateFlow()

    fun dismissTrustPrompt() { _trustPrompt.value = null }

    /** The user checked the fingerprint and confirmed: remember it, keep the address and test again. */
    fun confirmTrust(context: Context) {
        val prompt = _trustPrompt.value ?: return
        _trustPrompt.value = null
        viewModelScope.launch {
            settingsRepository.setRommUrl(prompt.url)
            trustService.trust(prompt.certificate.fingerprint)
            ToastUtil.showSuccess(context, context.getString(R.string.romm_cert_trusted))
            loadPlatforms(context, announce = true)
        }
    }

    fun forgetTrust(context: Context) {
        viewModelScope.launch {
            trustService.forget()
            ToastUtil.showInfo(context, context.getString(R.string.romm_cert_forgotten))
        }
    }

    /** Reads the certificate of [url]; asks to trust it when the system does not. */
    fun checkCertificate(context: Context, url: String) {
        viewModelScope.launch {
            val cert = trustService.inspect(url.trim().trimEnd('/'))
            when {
                !CertTrust.isHttps(url) -> ToastUtil.showInfo(context, context.getString(R.string.romm_cert_not_https))
                cert == null -> ToastUtil.showError(context, context.getString(R.string.romm_cert_unreachable))
                cert.trustedBySystem -> ToastUtil.showSuccess(context, context.getString(R.string.romm_cert_system_trusted))
                else -> _trustPrompt.value = TrustPrompt(url.trim().trimEnd('/'), cert)
            }
        }
    }

    private fun isTlsProblem(e: Throwable): Boolean = generateSequence(e) { it.cause }.any { it is SSLException }

    private suspend fun offerTrustIfTlsProblem(e: Throwable, url: String) {
        if (!isTlsProblem(e) || !CertTrust.isHttps(url)) return
        trustService.inspect(url.trim().trimEnd('/'))?.takeIf { !it.trustedBySystem }
            ?.let { _trustPrompt.value = TrustPrompt(url.trim().trimEnd('/'), it) }
    }

    fun setMarkGames(context: Context, enabled: Boolean) = executeWithToast(context, TAG) { settingsRepository.setRommMarkGames(enabled) }

    fun refreshMarks() { viewModelScope.launch { libraryService.refresh() } }

    fun startCovers() = coverService.start()

    private val _platforms = MutableStateFlow<List<RommPlatform>>(emptyList())
    /** Platforms fetched from the server; empty until [loadPlatforms] succeeds. */
    val platforms: StateFlow<List<RommPlatform>> = _platforms.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    fun loadPlatforms(context: Context?, announce: Boolean = false) {
        viewModelScope.launch {
            _loading.value = true
            runCatching { client.platforms() }
                .onSuccess { list ->
                    _platforms.value = list.sortedBy { it.label.lowercase() }
                    if (announce && context != null) ToastUtil.showSuccess(context, context.getString(R.string.romm_connected, list.size))
                }
                .onFailure { e ->
                    if (announce && context != null) ToastUtil.showError(context, context.getString(R.string.romm_connection_failed, e.message ?: ""))
                    if (announce) offerTrustIfTlsProblem(e, settingsRepository.rommUrl.first())
                }
            _loading.value = false
        }
    }

    fun suggestionFor(consoleId: String): RommPlatform? = RommPlatformMapper.suggest(consoleId, _platforms.value)

    fun setUrl(context: Context, url: String) = executeWithToast(context, TAG) { settingsRepository.setRommUrl(url) }
    fun setToken(context: Context, token: String) = executeWithToast(context, TAG) { settingsRepository.setRommToken(token) }
    fun setAutoUpload(context: Context, enabled: Boolean) = executeWithToast(context, TAG) { settingsRepository.setRommAutoUpload(enabled) }
    fun setPlatform(context: Context, consoleId: String, platformId: Int?) =
        executeWithToast(context, TAG) { settingsRepository.updateRommPlatform(consoleId, platformId) }

    /** Maps every console that has no mapping yet to its suggested platform. */
    fun applySuggestions(context: Context) {
        val state = uiState.value
        executeWithToast(context, TAG) {
            var applied = 0
            state.consoles.filter { it.id !in state.platformMap }.forEach { console ->
                suggestionFor(console.id)?.let { settingsRepository.updateRommPlatform(console.id, it.id); applied++ }
            }
            ToastUtil.showInfo(context, context.getString(R.string.romm_suggestions_applied, applied))
        }
    }

    /** Tries [url] + [token] without saving them. */
    fun testConnection(context: Context, url: String, token: String) {
        viewModelScope.launch {
            runCatching { client.testConnection(url, token) }
                .onSuccess { ToastUtil.showSuccess(context, context.getString(R.string.romm_connected, it)) }
                .onFailure { e ->
                    ToastUtil.showError(context, context.getString(R.string.romm_connection_failed, e.message ?: ""))
                    offerTrustIfTlsProblem(e, url)
                }
        }
    }

    private companion object { const val TAG = "RommViewModel" }
}
