package app.slurp.download

import android.content.Context
import app.slurp.core.Site
import app.slurp.core.UrlSniffer
import app.slurp.data.Prefs
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * Guards every read and write of [pump]. All access happens inside the lock,
     * so the mutex is also what publishes the field between threads — there is
     * deliberately no separate @Volatile.
     */
    private val pumpLock = Mutex()
    private var pump: CoroutineJob? = null

    private lateinit var appContext: Context

    /**
     * One automatic engine update per window, however many jobs fail inside it.
     * Six hours is well under the rate at which yt-dlp actually ships fixes, so
     * a genuine breakage is still picked up the same day.
     */
    private const val ENGINE_UPDATE_COOLDOWN_MS = 6 * 60 * 60 * 1000L

    private val prefsRef by lazy { Prefs(appContext) }
    private fun prefs(): Prefs = prefsRef

    fun attach(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext

        // Restore whatever was queued when the process last died, then keep the
        // file in step with every change. Collecting the flow rather than
        // saving at each call site means no future edit can forget to persist.
        _jobs.value = QueueStore.load(appContext)
        scope.launch {
            _jobs.collect { QueueStore.save(appContext, it) }
        }
        // Deliberately no startPump() here. This runs from Application.onCreate,
        // where Android 12+ refuses a foreground-service start, so a pump
        // started from here would drain a restored queue with nothing keeping
        // the process alive — the exact death the queue is written to disk to
        // survive. [resume] does it instead, from the activity.
    }

    /**
     * Picks a restored queue back up. Called from the activity, because that is
     * where starting a foreground service is allowed.
     *
     * Every other entry into the pump ([submit], [retry]) already pairs
     * [wakeService] with [startPump] for the same reason. This is the third,
     * and it was missing: a queue reloaded from disk started downloading with
     * no service at all.
     */
    fun resume() {
        if (!::appContext.isInitialized) return
        if (_jobs.value.none { it.state == JobState.QUEUED }) return
        wakeService()
        startPump()
    }

    /**
     * Accepts anything: a bare URL, a share-sheet caption with a link buried in
     * it, or several links at once.
     *
     * Called from the main thread, from the activity that received the share or
     * the paste — which matters for the foreground service, see [wakeService].
     */
    fun submit(text: String, quality: Quality) {
        val urls = UrlSniffer.allUrls(text)
        if (urls.isEmpty()) {
            emit("No link in that text")
            return
        }

        // Placeholders go in before the service starts, rather than from inside
        // each probe coroutine. The service stops itself the moment it observes
        // an empty queue, so it must never be started against one.
        val pending = urls.map { url ->
            Job(
                id = UUID.randomUUID().toString(),
                url = url,
                title = url,
                site = Site.of(url),
                quality = quality,
                state = JobState.CHECKING,
                status = "Checking link…",
            )
        }
        _jobs.update { it + pending }

        wakeService()
        pending.forEach { placeholder -> scope.launch { admit(placeholder) } }
    }

    /**
     * Starts the foreground service on the caller's thread, while the activity
     * that triggered the submit is still visible.
     *
     * This used to happen inside the pump, after the probe — seconds of network
     * latency later, by which point the user has usually gone back to whichever
     * app they shared from. Starting a foreground service from the background
     * throws ForegroundServiceStartNotAllowedException on Android 12+, and it
     * was being thrown inside a coroutine with no handler, which takes the whole
     * app down rather than failing the one download.
     *
     * The runCatching is belt and braces: if a start is refused anyway, the
     * downloads still run, they just lose their protection from being killed.
     */
    private fun wakeService() {
        runCatching { DownloadService.start(appContext) }
    }

    /** Probe one URL, then turn its placeholder into one job or a playlist's worth. */
    private suspend fun admit(placeholder: Job) {
        try {
            Ytdlp.ensureInit(appContext)
            Ytdlp.initError.value?.let { error(it) }

            val result = Ytdlp.probe(placeholder.url, placeholder.processId)
            val total = result.items.size
            val expanded = result.items.mapIndexed { index, item ->
                Job(
                    id = UUID.randomUUID().toString(),
                    url = item.url,
                    title = item.title,
                    site = Site.of(item.url),
                    quality = placeholder.quality,
                    state = JobState.QUEUED,
                    batchLabel = if (result.isPlaylist) "${index + 1} / $total" else null,
                    thumbnail = item.thumbnail,
                )
            }

            // Swapping the placeholder out and checking it is still wanted have
            // to be one operation. A probe takes seconds, and cancelling during
            // "Checking" used to lose the race: the card went CANCELLED, then
            // the probe landed and replaced it with queued jobs, and the
            // download the user had just dismissed started anyway.
            var admitted = false
            _jobs.update { current ->
                val existing = current.firstOrNull { it.id == placeholder.id }
                if (existing == null || existing.state == JobState.CANCELLED) {
                    admitted = false
                    current
                } else {
                    admitted = true
                    current.flatMap { if (it.id == placeholder.id) expanded else listOf(it) }
                }
            }
            if (!admitted) return

            if (result.isPlaylist) {
                // Entries yt-dlp listed but gave no usable link for are dropped
                // by the probe. Say so — losing three of forty videos in
                // silence is worse than a slightly noisier snackbar.
                emit(
                    if (result.skipped > 0) {
                        "Queued $total from “${result.title}” — skipped " +
                            "${result.skipped} with no link"
                    } else {
                        "Queued $total from “${result.title}”"
                    }
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Killing the probe process makes it throw. That is a cancellation,
            // not a failure, and overwriting the card would hide the difference.
            if (stateOf(placeholder.id) == JobState.CANCELLED) return
            patch(placeholder.id) {
                val described = Ytdlp.describe(e)
                it.copy(
                    state = JobState.FAILED,
                    status = "",
                    error = described,
                    hint = Ytdlp.hintFor(described),
                )
            }
            return
        }

        startPump()
    }

    /**
     * Starts the single download pump, if one is not already running.
     *
     * The lock is what makes "one at a time" actually true. Without it this is a
     * check-then-act on a field touched from several Dispatchers.Default
     * threads: two probes finishing in the same instant — the normal case for a
     * multi-link paste — both see no pump, both start one, and two parallel
     * downloads from the same site earn exactly the rate-limit block that the
     * serialisation exists to avoid.
     */
    private fun startPump() {
        scope.launch {
            pumpLock.withLock {
                if (pump?.isActive == true) return@withLock
                pump = scope.launch { drain() }
            }
        }
    }

    /**
     * Runs queued jobs until there are none left.
     *
     * Retiring happens under [pumpLock] and only after a final re-check of the
     * queue, which closes the opposite race to the one above: a job admitted
     * between the loop finding nothing and this coroutine actually completing
     * would otherwise find `pump.isActive == true`, decline to start a pump of
     * its own, and then sit at "Queued" forever with nothing left to drain it.
     */
    private suspend fun drain() {
        while (true) {
            val next = _jobs.value.firstOrNull { it.state == JobState.QUEUED }
            if (next != null) {
                runJob(next)
                continue
            }
            val retired = pumpLock.withLock {
                if (_jobs.value.any { it.state == JobState.QUEUED }) {
                    false
                } else {
                    pump = null
                    true
                }
            }
            if (retired) return
        }
    }

    private suspend fun runJob(job: Job) {
        // The pump can be entered without a probe having run, and the probe is
        // the only other place that initialises the engine. A queue restored
        // from disk goes attach -> resume -> drain -> here, and retry() puts a
        // job straight back on the queue; neither passes through admit().
        //
        // YoutubeDL.execute does not wait for init, it throws
        // IllegalStateException("instance not initialized") — and on a cold
        // start the unpack takes seconds, so the pump won that race every time
        // and every restored job failed instantly with a bare Java message that
        // neither looksLikeStaleExtractor nor hintFor had anything to say about.
        Ytdlp.ensureInit(appContext)
        Ytdlp.initError.value?.let { error ->
            patch(job.id) {
                it.copy(
                    state = JobState.FAILED,
                    status = "",
                    error = error,
                    hint = Ytdlp.hintFor(error),
                )
            }
            return
        }

        patch(job.id) { it.copy(state = JobState.DOWNLOADING, progress = -1f, status = "Starting…") }
        // Not cacheDir. Android reclaims cache directories under storage
        // pressure, and a multi-gigabyte video part-file is exactly what
        // creates that pressure — the system would be free to delete the
        // download mid-flight. noBackupFilesDir is normal app storage that is
        // also excluded from cloud backup, which these temporary files should
        // never be part of.
        val workDir = File(appContext.noBackupFilesDir, "work/${job.id}")

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

            // Killing the process is not guaranteed to land. The library
            // registers the process id inside execute(), so a cancel arriving
            // before that finds nothing to destroy, and one arriving in the
            // instant the download finished has nothing left to destroy either.
            // Neither case throws, so without this the job the user just
            // dismissed was published to the gallery anyway. The progress
            // callback above has always made this check; the completion path
            // never did. A missing job — remove() cancels and drops the card —
            // counts the same.
            //
            // Past this point the check stops: publish() writes the file, and
            // once it has, DONE is the honest state whatever the card says.
            if (stateOf(job.id) != JobState.DOWNLOADING) return

            patch(job.id) { it.copy(state = JobState.SAVING, progress = 1f, status = "Saving…") }
            val saved = MediaStoreSink.publish(appContext, file, job.quality.isAudio, prefs())
            patch(job.id) {
                it.copy(
                    state = JobState.DONE,
                    savedAs = saved.name,
                    savedUri = saved.uri,
                    savedIn = saved.location,
                    status = "",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (stateOf(job.id) != JobState.CANCELLED) {
                val described = Ytdlp.describe(e)
                if (!recoverByUpdatingEngine(job, described)) {
                    patch(job.id) {
                        it.copy(
                            state = JobState.FAILED,
                            status = "",
                            error = described,
                            hint = Ytdlp.hintFor(described),
                        )
                    }
                }
            }
        } finally {
            workDir.deleteRecursively()
        }
    }

    /**
     * Tries to rescue a failed download by pulling a newer yt-dlp, then puts the
     * job back on the queue.
     *
     * Site breakage is the ordinary reason a download dies, and upstream
     * usually ships a fix within days — so "download failed" is very often
     * really "your extractor is old". Reporting that and leaving the user to go
     * find the menu item is a worse answer than simply doing it.
     *
     * Three guards stop it becoming a nuisance: only failures that look like an
     * extractor problem qualify, each job gets exactly one attempt
     * ([Job.engineRetried]), and an update only runs if one has not run
     * recently — so twenty failing jobs cost one update, not twenty.
     *
     * @return true when the job has been requeued and must not be marked failed.
     */
    private suspend fun recoverByUpdatingEngine(job: Job, error: String): Boolean {
        if (job.engineRetried) return false
        if (!Ytdlp.looksLikeStaleExtractor(error)) return false

        val prefs = prefs()
        val sinceLast = System.currentTimeMillis() - prefs.lastEngineUpdate
        if (prefs.lastEngineUpdate != 0L && sinceLast < ENGINE_UPDATE_COOLDOWN_MS) return false

        patch(job.id) {
            it.copy(status = "Might be a stale extractor — updating engine…", progress = -1f)
        }

        // updateNow records lastEngineUpdate itself, so the cooldown is written
        // by whoever performs the update rather than by each caller — the menu
        // item used to record it from the composition, which meant an update
        // finishing after the activity had gone was never written down at all.
        val result = runCatching { Ytdlp.updateNow(appContext) }
            .getOrElse { e -> "Update failed: ${Ytdlp.describe(e)}" }

        // A failed update leaves the same engine in place, so a retry would die
        // identically. Let the original error stand.
        if (result.startsWith("Update failed")) {
            emit(result)
            return false
        }

        emit("$result — retrying")
        patch(job.id) {
            it.copy(
                state = JobState.QUEUED,
                engineRetried = true,
                status = "Retrying after engine update",
                error = null,
                hint = null,
                progress = -1f,
            )
        }
        return true
    }

    fun cancel(id: String) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        patch(id) { it.copy(state = JobState.CANCELLED, status = "Cancelled", progress = -1f) }
        // CHECKING kills the probe, DOWNLOADING kills the download. Both are a
        // forked Python process registered under the same process id.
        if (job.state == JobState.CHECKING || job.state == JobState.DOWNLOADING) Ytdlp.cancel(job)
    }

    fun retry(id: String) {
        patch(id) {
            it.copy(state = JobState.QUEUED, error = null, hint = null, status = "", progress = -1f)
        }
        wakeService()
        startPump()
    }

    fun remove(id: String) {
        cancel(id)
        _jobs.update { list -> list.filterNot { it.id == id } }
    }

    fun clearFinished() {
        _jobs.update { list -> list.filterNot { it.state.isTerminal } }
    }

    private fun stateOf(id: String): JobState? =
        _jobs.value.firstOrNull { it.id == id }?.state

    private fun patch(id: String, transform: (Job) -> Job) {
        _jobs.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private fun emit(message: String) {
        scope.launch { _messages.emit(message) }
    }
}
