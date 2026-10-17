package com.theveloper.pixelplay.presentation.download

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.data.download.DownloadJob
import com.theveloper.pixelplay.data.download.ExtTrack
import com.theveloper.pixelplay.data.download.InstalledExtension
import com.theveloper.pixelplay.data.download.JobStatus
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    viewModel: DownloadViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onOpenExtensions: () -> Unit,
) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.onFolderPicked(it.toString()) }
    }

    LaunchedEffect(ui.error) {
        ui.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }
    LaunchedEffect(ui.jobs) { viewModel.checkPendingAuth() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.download_title),
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = onOpenExtensions) {
                        Icon(
                            Icons.Rounded.Extension,
                            contentDescription = stringResource(R.string.download_cd_extensions),
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                // Clear the main screen's bottom nav + mini player overlay.
                .padding(bottom = MiniPlayerHeight + 24.dp)
                .padding(horizontal = 16.dp),
        ) {
            if (!ui.safFolderSet) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.download_no_folder),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = GoogleSansRounded,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { folderPicker.launch(null) }) {
                            Text(stringResource(R.string.download_pick_folder), fontFamily = GoogleSansRounded)
                        }
                    }
                }
            }

            SmoothOutlinedField(
                value = ui.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.padding(vertical = 4.dp),
                placeholder = stringResource(R.string.download_search_hint),
                enabled = !ui.searching,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search(ui.query) }),
                trailingIcon = {
                    if (ui.searching) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { viewModel.search(ui.query) }) {
                            Icon(
                                Icons.Rounded.Search,
                                contentDescription = stringResource(R.string.download_cd_search),
                            )
                        }
                    }
                },
            )

            // Results
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(ui.results, key = { "${it.providerId}:${it.id}" }) { track ->
                    DownloadResultRow(track = track, onEnqueue = { viewModel.requestDownload(track) })
                }
            }

            HorizontalDivider()

            // Queue
            Text(
                text = stringResource(R.string.download_queue),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ui.jobs, key = { it.itemId }) { job ->
                    DownloadJobRow(job = job, viewModel = viewModel)
                }
            }
        }

        ui.authPrompt?.let { prompt ->
            AuthCodeDialog(
                prompt = prompt,
                onSubmit = viewModel::submitAuthCode,
                onDismiss = viewModel::dismissAuth,
            )
        }

        ui.sheetTrack?.let { track ->
            DownloadOptionsSheet(
                track = track,
                providers = ui.downloadProviders,
                onDownload = { providerId, quality -> viewModel.enqueue(track, quality, providerId) },
                onDismiss = viewModel::dismissDownloadSheet,
            )
        }
    }
}

@Composable
private fun DownloadResultRow(track: ExtTrack, onEnqueue: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = track.coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.name,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = GoogleSansRounded,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (track.albumName.isEmpty()) track.artists
                else stringResource(R.string.download_artists_album, track.artists, track.albumName),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = GoogleSansRounded,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (track.providerId.isNotEmpty()) {
                AssistChip(
                    onClick = {},
                    label = { Text(track.providerId, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.heightIn(min = 24.dp),
                )
            }
        }
        IconButton(onClick = onEnqueue) {
            Icon(
                Icons.Rounded.Download,
                contentDescription = stringResource(R.string.download_cd_download),
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun DownloadJobRow(job: DownloadJob, viewModel: DownloadViewModel) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { job.progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = job.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = GoogleSansRounded,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = bytesLine(job, context),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = GoogleSansRounded,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (job.status) {
                JobStatus.QUEUED, JobStatus.DOWNLOADING ->
                    TextButton(onClick = { viewModel.cancel(job.itemId) }) {
                        Text(stringResource(R.string.download_cancel), fontFamily = GoogleSansRounded)
                    }
                JobStatus.FAILED -> {
                    TextButton(onClick = { viewModel.retry(job.itemId) }) {
                        Text(stringResource(R.string.download_retry), fontFamily = GoogleSansRounded)
                    }
                    TextButton(onClick = { viewModel.dismiss(job.itemId) }) {
                        Text(stringResource(R.string.download_dismiss), fontFamily = GoogleSansRounded)
                    }
                }
                JobStatus.COMPLETED, JobStatus.CANCELLED ->
                    TextButton(onClick = { viewModel.dismiss(job.itemId) }) {
                        Text(stringResource(R.string.download_dismiss), fontFamily = GoogleSansRounded)
                    }
            }
        }
    }
}

private fun bytesLine(job: DownloadJob, context: android.content.Context): String {
    val status = when (job.status) {
        JobStatus.QUEUED -> context.getString(R.string.download_status_queued)
        JobStatus.DOWNLOADING -> context.getString(R.string.download_status_downloading)
        JobStatus.COMPLETED -> context.getString(R.string.download_status_completed)
        JobStatus.FAILED -> context.getString(R.string.download_status_failed)
        JobStatus.CANCELLED -> context.getString(R.string.download_status_cancelled)
    }
    val received = android.text.format.Formatter.formatShortFileSize(context, job.bytesReceived)
    return if (job.bytesTotal > 0) {
        "$status · $received / ${android.text.format.Formatter.formatShortFileSize(context, job.bytesTotal)}"
    } else {
        "$status · $received"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadOptionsSheet(
    track: ExtTrack,
    providers: List<InstalledExtension>,
    onDownload: (providerId: String, quality: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedProvider by remember(track) {
        mutableStateOf(providers.firstOrNull()?.id.orEmpty())
    }
    val qualityOptions = remember(selectedProvider, providers) {
        providers.firstOrNull { it.id == selectedProvider }?.manifest?.qualityOptions.orEmpty()
    }
    var selectedQuality by remember(selectedProvider) {
        mutableStateOf(qualityOptions.firstOrNull()?.id ?: "flac")
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Text(
                text = stringResource(R.string.download_options_title),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = track.name,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = GoogleSansRounded,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )

            Text(
                text = stringResource(R.string.download_options_extension),
                style = MaterialTheme.typography.labelLarge,
                fontFamily = GoogleSansRounded,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            if (providers.isEmpty()) {
                Text(
                    text = stringResource(R.string.download_options_no_provider),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = GoogleSansRounded,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                providers.forEach { ext ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { selectedProvider = ext.id }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selectedProvider == ext.id, onClick = { selectedProvider = ext.id })
                        Column {
                            Text(ext.displayName, style = MaterialTheme.typography.bodyMedium, fontFamily = GoogleSansRounded)
                            Text(
                                text = ext.id,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = GoogleSansRounded,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (qualityOptions.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.download_options_quality),
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = GoogleSansRounded,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                qualityOptions.forEach { q ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { selectedQuality = q.id }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selectedQuality == q.id, onClick = { selectedQuality = q.id })
                        Column {
                            Text(
                                text = q.label.ifEmpty { q.id },
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = GoogleSansRounded,
                            )
                            if (q.description.isNotEmpty()) {
                                Text(
                                    text = q.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = GoogleSansRounded,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Button(
                onClick = { onDownload(selectedProvider, selectedQuality) },
                enabled = providers.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 32.dp),
                shape = RoundedCornerShape(20.dp),
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.download_options_download), fontFamily = GoogleSansRounded)
            }
        }
    }
}

@Composable
private fun AuthCodeDialog(prompt: String, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.download_auth_title), fontFamily = GoogleSansRounded) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.download_auth_message, prompt),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = GoogleSansRounded,
                )
                Spacer(Modifier.height(12.dp))
                SmoothOutlinedField(
                    value = code,
                    onValueChange = { code = it },
                    label = stringResource(R.string.download_auth_code_label),
                    isError = false,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (code.isNotBlank()) onSubmit(code.trim()) },
                enabled = code.isNotBlank(),
            ) {
                Text(stringResource(R.string.download_auth_submit), fontFamily = GoogleSansRounded)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.download_auth_cancel), fontFamily = GoogleSansRounded)
            }
        },
    )
}
