package com.theveloper.pixelplay.data.download

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadSettings @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("download_prefs", Context.MODE_PRIVATE)
    val rootDir: File get() = File(context.filesDir, "downloads").apply { mkdirs() }

    /** SAF tree URI of the user-chosen destination folder; null until picked. */
    var safTreeUri: String?
        get() = prefs.getString(KEY_TREE_URI, null)
        set(value) = prefs.edit().putString(KEY_TREE_URI, value).apply()

    var defaultQuality: String
        get() = prefs.getString(KEY_QUALITY, "flac") ?: "flac"
        set(value) = prefs.edit().putString(KEY_QUALITY, value).apply()

    /** Extension registry URL entered by the user; null until set. */
    var registryUrl: String?
        get() = prefs.getString(KEY_REGISTRY_URL, null)
        set(value) = prefs.edit().putString(KEY_REGISTRY_URL, value).apply()

    fun clearSafTree() = prefs.edit().remove(KEY_TREE_URI).apply()

    private companion object {
        const val KEY_TREE_URI = "saf_tree_uri"
        const val KEY_QUALITY = "default_quality"
        const val KEY_REGISTRY_URL = "registry_url"
    }
}
