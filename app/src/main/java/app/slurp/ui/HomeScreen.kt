package app.slurp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.slurp.data.Prefs
import app.slurp.download.DownloadQueue
import app.slurp.engine.Ytdlp
import app.slurp.model.Job
import app.slurp.model.JobState
import app.slurp.model.Quality

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    prefs: Prefs,
    pendingLink: String?,
    onLinkConsumed: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf(prefs.quality) }

    // Update state lives on Ytdlp, not here. Held in the composition it was
    // cancelled by a rotation, midway through replacing the yt-dlp binary.
    val updating by Ytdlp.updating.collectAsStateWithLifecycle()

    val jobs by DownloadQueue.jobs.collectAsStateWithLifecycle()
    val engineReady by Ytdlp.ready.collectAsStateWithLifecycle()
    val engineError by Ytdlp.initError.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbars = remember { SnackbarHostState() }

    // A link arriving from the share sheet that was not auto-started lands in
    // the field rather than vanishing.
    LaunchedEffect(pendingLink) {
        pendingLink?.let {
            input = it
            onLinkConsumed()
        }
    }

    LaunchedEffect(Unit) {
        DownloadQueue.messages.collect { snackbars.showSnackbar(it) }
    }

    LaunchedEffect(Unit) {
        Ytdlp.updateResults.collect { result ->
            prefs.lastEngineUpdate = System.currentTimeMillis()
            snackbars.showSnackbar(result)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text("slurp", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    if (jobs.any { it.state.isTerminal }) {
                        TextButton(onClick = { DownloadQueue.clearFinished() }) { Text("Clear") }
                    }
                    TextButton(
                        enabled = !updating,
                        onClick = { Ytdlp.requestUpdate(context) },
                    ) { Text(if (updating) "Updating…" else "Update") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (engineError != null) {
                EngineErrorBanner(engineError!!)
            }

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Paste a link") },
                placeholder = { Text("YouTube · TikTok · Instagram · Facebook · Threads · X") },
                maxLines = 3,
                trailingIcon = {
                    if (input.isNotEmpty()) {
                        IconButton(onClick = { input = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Quality.entries.forEach { option ->
                    FilterChip(
                        selected = quality == option,
                        onClick = {
                            quality = option
                            prefs.quality = option
                        },
                        label = { Text(option.label) },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { readClipboard(context)?.let { input = it } },
                ) { Text("Paste") }

                Button(
                    modifier = Modifier.weight(1f),
                    enabled = input.isNotBlank(),
                    onClick = {
                        DownloadQueue.submit(input, quality)
                        input = ""
                    },
                ) {
                    Text(if (!engineReady) "Download (engine starting…)" else "Download")
                }
            }

            if (jobs.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(jobs, key = { it.id }) { job -> JobCard(job) }
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Text(
            text = "Nothing queued.\n\nShare a link to slurp from any app, " +
                "or paste one above. Playlists queue every video.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 32.dp),
        )
    }
}

@Composable
private fun EngineErrorBanner(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "The download engine did not start",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onError,
            )
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onError,
            )
        }
    }
}

@Composable
private fun JobCard(job: Job) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            job.site.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        job.batchLabel?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        job.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                when {
                    job.state == JobState.CHECKING ->
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)

                    job.state == JobState.FAILED ->
                        IconButton(onClick = { DownloadQueue.retry(job.id) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Retry")
                        }

                    job.state.isTerminal ->
                        IconButton(onClick = { DownloadQueue.remove(job.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove")
                        }

                    else ->
                        IconButton(onClick = { DownloadQueue.cancel(job.id) }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                        }
                }
            }

            if (job.state == JobState.DOWNLOADING || job.state == JobState.SAVING) {
                if (job.progress >= 0f) {
                    LinearProgressIndicator(
                        progress = { job.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            SubLine(job)
        }
    }
}

@Composable
private fun SubLine(job: Job) {
    val text = when (job.state) {
        JobState.DONE -> "Saved · ${job.savedAs.orEmpty()}"
        JobState.FAILED -> job.error ?: "Failed"
        JobState.CANCELLED -> "Cancelled"
        JobState.QUEUED -> "Queued"
        else -> listOfNotNull(
            Format.percent(job.progress),
            Format.eta(job.etaSeconds),
            job.status.takeIf { it.isNotBlank() },
        ).joinToString("  ·  ")
    }

    if (text.isBlank() && job.hint == null) return

    if (text.isNotBlank()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (job.state == JobState.DOWNLOADING) FontFamily.Monospace else null,
            color = when (job.state) {
                JobState.FAILED -> MaterialTheme.colorScheme.error
                JobState.DONE -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }

    // yt-dlp's own error line is precise but says nothing a person can act on.
    // The hint is the "so do this" underneath it.
    job.hint?.takeIf { job.state == JobState.FAILED }?.let { hint ->
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
