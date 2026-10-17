package com.theveloper.pixelplay.data.download

import kotlinx.serialization.Serializable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Search result bundle */
data class SearchResult(val tracks: List<ExtTrack>)

sealed interface BackendResult<out T> {
    data class Ok<T>(val value: T) : BackendResult<T>
    data class Failed(val response: DownloadResponse) : BackendResult<Nothing>
}

interface DownloadBackendApi {
    fun initIfNeeded()
    fun listInstalled(): List<InstalledExtension>
    fun installFromFile(path: String): BackendResult<InstalledExtension?>
    fun remove(id: String)
    fun setEnabled(id: String, enabled: Boolean)
    fun search(query: String, limit: Int = 30): List<ExtTrack>
    fun download(request: DownloadRequest): BackendResult<DownloadResponse>
    fun progressDelta(sinceSeq: Long): ProgressDelta
    fun waitProgressDelta(sinceSeq: Long, timeoutMs: Long): ProgressDelta
    fun cancel(itemId: String)
    fun pendingAuth(extensionId: String): String
    fun submitAuthCode(extensionId: String, code: String)
}

@Singleton
class GobackendDownloadBackend @Inject constructor(
    private val settings: DownloadSettings,
) : DownloadBackendApi {

    private val lock = Any()
    private var initialized = false

    override fun initIfNeeded() {
        synchronized(lock) {
            if (initialized) return
            runCatching {
                val extDir = File(settings.rootDir, "extensions")
                val dataDir = File(settings.rootDir, "download_data")
                extDir.mkdirs(); dataDir.mkdirs()
                gobackend.Gobackend.initExtensionSystem(extDir.absolutePath, dataDir.absolutePath)
                gobackend.Gobackend.loadExtensionsFromDir(extDir.absolutePath)
            }.onSuccess { initialized = true }
        }
    }

    override fun listInstalled(): List<InstalledExtension> {
        initIfNeeded()
        val raw = gobackend.Gobackend.getInstalledExtensions()
        return DownloadJson.decodeFromString(raw.ifEmpty { "[]" })
    }

    override fun installFromFile(path: String): BackendResult<InstalledExtension?> {
        initIfNeeded()
        return runCatching {
            val raw = gobackend.Gobackend.loadExtensionFromPath(path)
            val id = DownloadJson.decodeFromString<InstallResult>(raw.ifEmpty { "{}" })
            BackendResult.Ok(listInstalled().firstOrNull { it.id == id.name })
        }.getOrElse {
            BackendResult.Failed(
                DownloadResponse(success = false, error = it.message, errorType = "install_failed"),
            )
        }
    }

    override fun remove(id: String) { initIfNeeded(); gobackend.Gobackend.removeExtensionByID(id) }

    override fun setEnabled(id: String, enabled: Boolean) {
        initIfNeeded(); gobackend.Gobackend.setExtensionEnabledByID(id, enabled)
    }

    override fun search(query: String, limit: Int): List<ExtTrack> {
        initIfNeeded()
        val raw = gobackend.Gobackend.searchTracksWithMetadataProvidersJSON(query, limit.toLong(), true)
        return runCatching { DownloadJson.decodeFromString<List<ExtTrack>>(raw) }.getOrDefault(emptyList())
    }

    override fun download(request: DownloadRequest): BackendResult<DownloadResponse> {
        initIfNeeded()
        return runCatching {
            val raw = gobackend.Gobackend.downloadByStrategy(DownloadJson.encodeToString(request))
            DownloadJson.decodeFromString<DownloadResponse>(raw)
        }.fold(
            onSuccess = { resp -> if (resp.success) BackendResult.Ok(resp) else BackendResult.Failed(resp) },
            onFailure = { BackendResult.Failed(DownloadResponse(success = false, error = it.message, errorType = "backend_crash")) },
        )
    }

    override fun progressDelta(sinceSeq: Long): ProgressDelta {
        val raw = gobackend.Gobackend.getAllDownloadProgressDelta(sinceSeq)
        return runCatching { DownloadJson.decodeFromString<ProgressDelta>(raw) }
            .getOrDefault(ProgressDelta(seq = sinceSeq))
    }

    override fun waitProgressDelta(sinceSeq: Long, timeoutMs: Long): ProgressDelta {
        val raw = gobackend.Gobackend.waitForAllDownloadProgressDelta(sinceSeq, timeoutMs)
        return runCatching { DownloadJson.decodeFromString<ProgressDelta>(raw) }
            .getOrDefault(ProgressDelta(seq = sinceSeq))
    }

    override fun cancel(itemId: String) { gobackend.Gobackend.cancelDownload(itemId) }

    override fun pendingAuth(extensionId: String): String {
        return runCatching { gobackend.Gobackend.getExtensionPendingAuthJSON(extensionId) }.getOrDefault("{}")
    }

    override fun submitAuthCode(extensionId: String, code: String) {
        gobackend.Gobackend.setExtensionAuthCodeByID(extensionId, code)
    }
}

@Serializable
private data class InstallResult(val name: String = "", val success: Boolean = false, val error: String? = null)
