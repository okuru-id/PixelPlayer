package com.theveloper.pixelplay.presentation.download

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.download.BackendResult
import com.theveloper.pixelplay.data.download.DownloadBackendApi
import com.theveloper.pixelplay.data.download.InstalledExtension
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ExtensionsViewModel @Inject constructor(
    private val backend: DownloadBackendApi,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    data class UiState(
        val extensions: List<InstalledExtension> = emptyList(),
        val installing: Boolean = false,
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        load()
    }

    fun load() = viewModelScope.launch(Dispatchers.IO) {
        val extensions = runCatching { backend.listInstalled() }.getOrDefault(emptyList())
        _ui.update { it.copy(extensions = extensions) }
    }

    /**
     * Copies the picked document to a cache temp file before handing it to the
     * backend: .spotiflac-ext documents report opaque MIME types, so the
     * extension suffix is the only reliable signal for LoadExtensionFromPath.
     */
    fun install(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        _ui.update { it.copy(installing = true) }
        val temp = File(context.cacheDir, "extension-${System.currentTimeMillis()}.spotiflac-ext")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            } ?: run {
                _ui.update { it.copy(error = context.getString(R.string.extensions_install_failed)) }
                return@launch
            }
            when (val result = backend.installFromFile(temp.absolutePath)) {
                is BackendResult.Failed -> _ui.update {
                    it.copy(error = result.response.error ?: context.getString(R.string.extensions_install_failed))
                }
                is BackendResult.Ok -> load()
            }
        } catch (e: Exception) {
            _ui.update { it.copy(error = context.getString(R.string.extensions_install_failed)) }
        } finally {
            temp.delete()
            _ui.update { it.copy(installing = false) }
        }
    }

    fun remove(id: String) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { backend.remove(id) }
            .onFailure { t -> _ui.update { it.copy(error = t.message ?: context.getString(R.string.extensions_install_failed)) } }
        load()
    }

    fun toggle(id: String, enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        runCatching { backend.setEnabled(id, enabled) }
            .onFailure { t -> _ui.update { it.copy(error = t.message ?: context.getString(R.string.extensions_install_failed)) } }
        load()
    }

    fun consumeError() = _ui.update { it.copy(error = null) }
}
