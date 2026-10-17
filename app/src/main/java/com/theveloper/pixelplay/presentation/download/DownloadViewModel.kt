package com.theveloper.pixelplay.presentation.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.download.DownloadBackendApi
import com.theveloper.pixelplay.data.download.DownloadJob
import com.theveloper.pixelplay.data.download.DownloadJson
import com.theveloper.pixelplay.data.download.DownloadRepository
import com.theveloper.pixelplay.data.download.DownloadSettings
import com.theveloper.pixelplay.data.download.DownloadWorker
import com.theveloper.pixelplay.data.download.ExtTrack
import com.theveloper.pixelplay.data.download.JobStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape of GetExtensionPendingAuthJSON / GetAllPendingAuthRequestsJSON
 * (SpotiFLAC go_backend exports_extensions.go): {"extension_id","auth_url",
 * "callback_url"}. There is no prompt text — the auth_url IS the prompt.
 */
@Serializable
private data class PendingAuth(
    @SerialName("extension_id") val extensionId: String = "",
    @SerialName("auth_url") val authUrl: String = "",
    @SerialName("callback_url") val callbackUrl: String = "",
)

@HiltViewModel
class DownloadViewModel @Inject constructor(
    private val backend: DownloadBackendApi,
    private val repository: DownloadRepository,
    private val settings: DownloadSettings,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    data class UiState(
        val query: String = "",
        val results: List<ExtTrack> = emptyList(),
        val searching: Boolean = false,
        val error: String? = null,
        val jobs: List<DownloadJob> = emptyList(),
        val safFolderSet: Boolean = false,
        val authRequestId: String? = null,
        val authPrompt: String? = null,
    )

    private val _ui = MutableStateFlow(UiState(safFolderSet = settings.safTreeUri != null))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            repository.jobs.collect { jobs ->
                _ui.update { it.copy(jobs = jobs, safFolderSet = settings.safTreeUri != null) }
                // Surface the auth dialog whenever a download fails with
                // errorType == verification_required (e.g. Spotify/Amazon need
                // login verification before downloading).
                checkPendingAuth()
            }
        }
    }

    fun onQueryChange(query: String) = _ui.update { it.copy(query = query) }

    fun search(query: String) = viewModelScope.launch(Dispatchers.IO) {
        _ui.update { it.copy(query = query, searching = true) }
        val tracks = runCatching { backend.search(query) }.getOrElse { emptyList() }
        _ui.update {
            it.copy(
                results = tracks,
                searching = false,
                error = if (tracks.isEmpty()) context.getString(R.string.download_no_results) else null,
            )
        }
    }

    fun enqueue(track: ExtTrack) {
        if (settings.safTreeUri == null) {
            _ui.update { it.copy(error = context.getString(R.string.download_pick_folder_first)) }
            return
        }
        repository.enqueue(track, settings.defaultQuality)
        DownloadWorker.enqueue(context)
    }

    fun onFolderPicked(uri: String) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        settings.safTreeUri = uri
        _ui.update { it.copy(safFolderSet = true) }
    }

    /** Called when a FAILED job with errorType "verification_required" appears. */
    fun checkPendingAuth() {
        val state = _ui.value
        if (state.authRequestId != null) return
        val job = state.jobs.firstOrNull {
            it.status == JobStatus.FAILED && it.errorType == "verification_required"
        } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            // Job has no downloadProvider field; the extension id lives on the track.
            val extId = job.track.providerId
            val pending = if (extId.isNotEmpty()) {
                decodePendingAuth(backend.pendingAuth(extId))
            } else {
                decodeAllPendingAuth().firstOrNull { it.authUrl.isNotEmpty() }
            }
            if (pending != null && pending.authUrl.isNotEmpty()) {
                _ui.update {
                    it.copy(authRequestId = pending.extensionId.ifEmpty { extId }, authPrompt = pending.authUrl)
                }
            }
        }
    }

    private fun decodePendingAuth(json: String): PendingAuth? =
        runCatching { DownloadJson.decodeFromString<PendingAuth>(json.ifEmpty { "{}" }) }.getOrNull()
            ?.takeIf { it.authUrl.isNotEmpty() || it.extensionId.isNotEmpty() }

    /** Fallback when the extension id is unknown: list every pending auth request. */
    private fun decodeAllPendingAuth(): List<PendingAuth> =
        runCatching {
            DownloadJson.decodeFromString<List<PendingAuth>>(gobackend.Gobackend.getAllPendingAuthRequestsJSON())
        }.getOrDefault(emptyList())

    fun submitAuthCode(code: String) {
        val id = _ui.value.authRequestId ?: return
        _ui.update { it.copy(authRequestId = null, authPrompt = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { backend.submitAuthCode(id, code) }
            // Retry the failed downloads now that the extension is authenticated.
            _ui.value.jobs
                .filter { it.errorType == "verification_required" && it.track.providerId == id }
                .forEach { repository.retry(it.itemId) }
            DownloadWorker.enqueue(context)
        }
    }

    fun cancel(itemId: String) {
        viewModelScope.launch(Dispatchers.IO) { runCatching { backend.cancel(itemId) } }
        repository.markCancelled(itemId)
    }

    fun retry(itemId: String) {
        repository.retry(itemId)
        DownloadWorker.enqueue(context)
    }

    fun dismiss(itemId: String) = repository.dismiss(itemId)

    fun consumeError() = _ui.update { it.copy(error = null) }

    fun dismissAuth() = _ui.update { it.copy(authRequestId = null, authPrompt = null) }
}
