package com.theveloper.pixelplay.data.download

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire models for the gobackend JSON API. Field names MUST match the Go
 * structs in SpotiFLAC-Mobile's go_backend (exports_download.go,
 * extension_provider_types.go, progress.go, extension_manager.go).
 */
@Serializable
data class DownloadRequest(
    val service: String,
    @SerialName("track_name") val trackName: String,
    @SerialName("artist_name") val artistName: String,
    @SerialName("album_name") val albumName: String,
    @SerialName("album_artist") val albumArtist: String = "",
    @SerialName("cover_url") val coverUrl: String = "",
    @SerialName("output_path") val outputPath: String,
    @SerialName("output_ext") val outputExt: String = "",
    @SerialName("filename_format") val filenameFormat: String = "{tracknumber} - {artist} - {title}",
    val quality: String,
    @SerialName("embed_metadata") val embedMetadata: Boolean = true,
    @SerialName("item_id") val itemId: String,
    @SerialName("duration_ms") val durationMs: Int = 0,
    @SerialName("track_number") val trackNumber: Int = 0,
    @SerialName("disc_number") val discNumber: Int = 0,
    @SerialName("total_tracks") val totalTracks: Int = 0,
    @SerialName("total_discs") val totalDiscs: Int = 0,
    @SerialName("release_date") val releaseDate: String = "",
    val isrc: String = "",
    @SerialName("spotify_id") val spotifyId: String = "",
    @SerialName("provider_track_id") val providerTrackId: String = "",
    @SerialName("download_provider") val downloadProvider: String = "",
    // Spec: built-in providers are retired; extension mode is mandatory.
    @SerialName("use_extensions") val useExtensions: Boolean = true,
    @SerialName("use_fallback") val useFallback: Boolean = false,
)

@Serializable
data class DownloadResponse(
    val success: Boolean = false,
    val message: String = "",
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("resolved_file_name") val resolvedFileName: String? = null,
    val error: String? = null,
    @SerialName("error_type") val errorType: String? = null,
    @SerialName("retry_after_seconds") val retryAfterSeconds: Int = 0,
    @SerialName("already_exists") val alreadyExists: Boolean = false,
    @SerialName("actual_extension") val actualExtension: String? = null,
    val service: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
)

@Serializable
data class ItemProgress(
    @SerialName("item_id") val itemId: String,
    @SerialName("bytes_total") val bytesTotal: Long = 0,
    @SerialName("bytes_received") val bytesReceived: Long = 0,
    val progress: Float = 0f,
    @SerialName("speed_mbps") val speedMbps: Float = 0f,
    @SerialName("is_downloading") val isDownloading: Boolean = false,
    val status: String = "",
    val stage: String? = null,
)

@Serializable
data class ProgressDelta(
    val seq: Long,
    val reset: Boolean = false,
    val items: Map<String, ItemProgress> = emptyMap(),
    val removed: List<String> = emptyList(),
)

@Serializable
data class ExtTrack(
    val id: String,
    val name: String,
    val artists: String = "",
    @SerialName("album_name") val albumName: String = "",
    @SerialName("album_artist") val albumArtist: String = "",
    @SerialName("duration_ms") val durationMs: Int = 0,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("provider_id") val providerId: String = "",
    val isrc: String? = null,
    @SerialName("spotify_id") val spotifyId: String? = null,
    @SerialName("provider_track_id") val providerTrackId: String? = null,
    @SerialName("track_number") val trackNumber: Int = 0,
    @SerialName("disc_number") val discNumber: Int = 0,
    @SerialName("total_tracks") val totalTracks: Int = 0,
    @SerialName("total_discs") val totalDiscs: Int = 0,
    @SerialName("release_date") val releaseDate: String? = null,
    val genre: String? = null,
    @SerialName("album_type") val albumType: String? = null,
    val explicit: Boolean = false,
)

@Serializable
data class ExtensionManifest(
    val name: String = "",
    @SerialName("display_name") val displayName: String = "",
    val version: String = "",
    val description: String? = null,
)

@Serializable
data class InstalledExtension(
    val id: String,
    val manifest: ExtensionManifest = ExtensionManifest(),
    val enabled: Boolean = false,
    val status: String = "",
    val error: String? = null,
) {
    val displayName: String get() = manifest.displayName.ifEmpty { manifest.name.ifEmpty { id } }
    val version: String get() = manifest.version
}

/**
 * Entry from the extension registry. The Go repoExtension struct emits BOTH
 * snake_case and camelCase keys (alt fields), so ignoreUnknownKeys +
 * snake_case SerialNames is the correct pairing. Defaults everywhere so a
 * partial registry entry never breaks parsing.
 */
@Serializable
data class RepoExtension(
    val id: String = "",
    val name: String = "",
    @SerialName("display_name") val displayName: String = "",
    val version: String = "",
    val description: String? = null,
    @SerialName("download_url") val downloadUrl: String = "",
    @SerialName("icon_url") val iconUrl: String? = null,
    val category: String = "",
    val sha256: String = "",
    @SerialName("min_app_version") val minAppVersion: String = "",
)

val DownloadJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
