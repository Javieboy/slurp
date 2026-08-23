package app.slurp.download

import android.content.Context
import app.slurp.core.Site
import app.slurp.core.UrlSniffer
import app.slurp.engine.Ytdlp
import app.slurp.model.Job
import app.slurp.model.JobState
import app.slurp.model.Quality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Job as CoroutineJob

/**
 * The whole download pipeline: text in, files in the gallery out.
 *
 * Downloads run strictly one at a time. That is a deliberate choice rather than
 * a simplification — every one of the target sites rate-limits aggressively,
 * and three parallel downloads from Instagram reliably earns a temporary block
 * that looks, from inside the app, exactly like the extractor being broken.
 */
object DownloadQueue {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs = _jobs.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages = _messages.asSharedFlow()

    private var pump: CoroutineJob? = null
    private lateinit var appContext: Context

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Accepts anything: a bare URL, a share-sheet caption with a link buried in
     * it, or several links at once.
     */
    fun submit(text: String, quality: Quality) {
        val urls = UrlSniffer.allUrls(text)
        if (urls.isEmpty()) {
            emit("No link in that text")
            return
        }
        urls.forEach { url -> scope.launch { admit(url, quality) } }
    }

    /** Probe one URL, then turn it into one job or a playlist's worth of them. */
    private suspend fun admit(url: String, quality: Quality) {
        val placeholder = Job(
            id = UUID.randomUUID().toString(),
            url = url,
            title = url,
            site = Site.of(url),
            quality = quality,
            state = JobState.CHECKING,
            status = "Checking link…",
        )
        _jobs.update { it + placeholder }

        try {
            Ytdlp.ensureInit(appContext)
            Ytdlp.initError.value?.let { error(it) }

            val result = Ytdlp.probe(url)
            val total = result.items.size
            val expanded = result.items.mapIndexed { index, item ->
                Job(
                    id = UUID.randomUUID().toString(),
                    url = item.url,
                    title = item.title,
                    site = Site.of(item.url),
                    quality = quality,
                    state = JobState.QUEUED,
                    batchLabel = if (result.isPlaylist) "${index + 1} / $total" else null,
                )
            }
            _jobs.update { current ->
                current.flatMap { if (it.id == placeholder.id) expanded else listOf(it) }
            }
            if (result.isPlaylist) emit("Queued $total from “${result.title}”")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            patch(placeholder.id) {
                it.copy(state = JobState.FAILED, status = "", error = Ytdlp.describe(e))
            }
        }

        startPump()
    }

    private fun startPump() {
        if (pump?.isActive == true) return
        pump = scope.launch {
            DownloadService.start(appContext)
            try {
                while (true) {
                    val next = _jobs.value.firstOrNull { it.state == JobState.QUEUED } ?: break
                    runJob(next)
                }
            } finally {
                DownloadService.stop(appContext)
            }
        }
    }

    private suspend fun runJob(job: Job) {
        patch(job.id) { it.copy(state = JobState.DOWNLOADING, progress = -1f, status = "Starting…") }
        val workDir = File(appContext.cacheDir, "work/${job.id}")

        try {
            val file = Ytdlp.download(job, workDir) { progress, eta, line ->
                patch(job.id) { current ->
                    // A cancelled job can still receive a few trailing progress
                    // lines before the process actually dies. Ignore them, or
                    // the card flickers back to "downloading" after cancel.
                    if (current.state != JobState.DOWNLOADING) current
                    else current.copy(progress = progress, etaSeconds = eta, status = line.trim().take(140))
                }
            }

            patch(job.id) { it.copy(state = JobState.SAVING, progress = 1f, status = "Saving…") }
            val savedName = MediaStoreSink.publish(appContext, file, job.quality.isAudio)
            patch(job.id) { it.copy(state = JobState.DONE, savedAs = savedName, status = "") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val wasCancelled = _jobs.value.firstOrNull { it.id == job.id }?.state == JobState.CANCELLED
            if (!wasCancelled) {
                patch(job.id) {
                    it.copy(state = JobState.FAILED, status = "", error = Ytdlp.describe(e))
                }
            }
        } finally {
            workDir.deleteRecursively()
        }
    }

    fun cancel(id: String) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        patch(id) { it.copy(state = JobState.CANCELLED, status = "Cancelled", progress = -1f) }
        if (job.state == JobState.DOWNLOADING) Ytdlp.cancel(job)
    }

    fun retry(id: String) {
        patch(id) { it.copy(state = JobState.QUEUED, error = null, status = "", progress = -1f) }
        startPump()
    }

    fun remove(id: String) {
        cancel(id)
        _jobs.update { list -> list.filterNot { it.id == id } }
    }

    fun clearFinished() {
        _jobs.update { list -> list.filterNot { it.state.isTerminal } }
    }

    /** Jobs still doing something, used for the foreground notification. */
    fun activeCount(): Int = _jobs.value.count { !it.state.isTerminal }

    private fun patch(id: String, transform: (Job) -> Job) {
        _jobs.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private fun emit(message: String) {
        scope.launch { _messages.emit(message) }
    }
}
