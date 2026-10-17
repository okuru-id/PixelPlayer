package com.theveloper.pixelplay.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Serial download queue: claims queued jobs from [DownloadRepository], downloads
 * via gobackend to a cache work file, publishes the result into the user's SAF
 * folder, then media-scans it so the track lands in the local library.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val backend: DownloadBackendApi,
    private val repository: DownloadRepository,
    private val settings: DownloadSettings,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        ensureNotificationChannel(applicationContext)
        setForeground(createForegroundInfo("Starting download…"))
        while (true) {
            val job = repository.claimNextQueued() ?: break
            val treeUri = settings.safTreeUri ?: run {
                repository.markFailed(job.itemId, "No download folder selected", "no_folder")
                continue
            }
            process(job, treeUri)
        }
        Result.success()
    }

    private suspend fun process(job: DownloadJob, treeUri: String) {
        val workFile = File(File(applicationContext.cacheDir, "downloads").apply { mkdirs() }, "${job.itemId}.work")
        val request = DownloadRequest(
            service = "spotify",
            trackName = job.track.name,
            artistName = job.track.artists,
            albumName = job.track.albumName,
            albumArtist = job.track.albumArtist,
            coverUrl = job.track.coverUrl.orEmpty(),
            // Local cache file only — SAF publishing copies from it. Never output_fd.
            outputPath = workFile.absolutePath,
            outputExt = if (job.quality == "flac") "flac" else "",
            quality = job.quality,
            embedMetadata = true,
            itemId = job.itemId,
            durationMs = job.track.durationMs,
            trackNumber = job.track.trackNumber,
            discNumber = job.track.discNumber,
            totalTracks = job.track.totalTracks,
            totalDiscs = job.track.totalDiscs,
            releaseDate = job.track.releaseDate.orEmpty(),
            isrc = job.track.isrc.orEmpty(),
            spotifyId = job.track.spotifyId.orEmpty(),
            providerTrackId = job.track.providerTrackId.orEmpty(),
        )
        var seq = 0L
        val result = coroutineScope {
            val poller = launch {
                while (isActive) {
                    val delta = backend.waitProgressDelta(seq, 400)
                    seq = delta.seq
                    repository.applyDelta(delta)
                    updateNotification(job, delta)
                }
            }
            val r = backend.download(request)
            poller.cancel()
            r
        }
        workFile.delete() // gobackend may leave the work file behind on failure

        when (result) {
            is BackendResult.Failed ->
                repository.markFailed(job.itemId, result.response.error ?: "Download failed", result.response.errorType)
            is BackendResult.Ok -> {
                val path = result.value.filePath
                if (path == null) {
                    repository.markFailed(job.itemId, "Backend returned no file", "no_file")
                } else {
                    val src = File(path)
                    val ext = (result.value.actualExtension ?: job.quality).trimStart('.')
                    val target = publish(src, treeUri, job, ext)
                    src.delete()
                    if (target != null) {
                        repository.markCompleted(job.itemId, target.toString(), ext)
                        MediaScannerConnection.scanFile(applicationContext, arrayOf(target.toString()), arrayOf(mimeFor(ext)), null)
                        repository.markPublished(job.itemId)
                    }
                }
            }
        }
    }

    /** Copies [src] into the SAF tree as "<artists> - <title>.<ext>" (collision-safe). Returns target uri or null after marking failed. */
    private fun publish(src: File, treeUri: String, job: DownloadJob, ext: String): Uri? {
        val dir = DocumentFile.fromTreeUri(applicationContext, Uri.parse(treeUri))
            ?: run {
                repository.markFailed(job.itemId, "Download folder unavailable — reselect it", "no_folder")
                return null
            }
        val base = sanitize("${job.track.artists.ifEmpty { "Unknown artist" }} - ${job.track.name}")
        var name = "$base.$ext"
        var n = 1
        while (dir.findFile(name) != null) name = "$base (${n++}).$ext"
        val target = dir.createFile(mimeFor(ext), name)
            ?: run {
                repository.markFailed(job.itemId, "Could not create file in download folder", "saf_error")
                return null
            }
        try {
            applicationContext.contentResolver.openOutputStream(target.uri)?.use { out ->
                src.inputStream().use { it.copyTo(out, DEFAULT_BUFFER_SIZE * 8) }
            } ?: run {
                repository.markFailed(job.itemId, "Could not open target file", "saf_error")
                return null
            }
        } catch (e: Exception) {
            repository.markFailed(job.itemId, e.message ?: "Publish failed", "saf_error")
            return null
        }
        return target.uri
    }

    private fun sanitize(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(150)

    private fun mimeFor(ext: String) = when (ext) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        else -> "audio/*"
    }

    private fun updateNotification(job: DownloadJob, delta: ProgressDelta) {
        val p = delta.items[job.itemId] ?: return
        val text = "Downloading ${job.track.artists} - ${job.track.name} · ${p.status} ${(p.progress * 100).toInt()}%"
        NotificationManagerCompat.from(applicationContext).notify(NOTIF_ID, buildNotification(text))
    }

    // dataSync type must match the manifest override on androidx.work's
    // SystemForegroundService (required on API 34+; WorkManager ships none).
    private fun createForegroundInfo(text: String) = ForegroundInfo(
        NOTIF_ID,
        buildNotification(text),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    companion object {
        const val CHANNEL_ID = "download_channel"
        const val NOTIF_ID = 4001
        const val UNIQUE_WORK = "external_download"

        fun ensureNotificationChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music downloads",
                NotificationManager.IMPORTANCE_LOW,
            )
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        /** Enqueues the serial queue pass. APPEND_OR_REPLACE: appends to a running pass, replaces a dead one. */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<DownloadWorker>().build(),
            )
        }
    }
}
