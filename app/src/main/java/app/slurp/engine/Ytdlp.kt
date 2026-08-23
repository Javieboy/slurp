package app.slurp.engine

import android.content.Context
import android.util.Log
import app.slurp.model.Job
import app.slurp.model.ProbeItem
import app.slurp.model.ProbeResult
import app.slurp.model.ProbeRoot
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The bundled yt-dlp, wrapped in something suspend-friendly.
 *
 * Everything here is on [Dispatchers.IO] without exception. The underlying
 * library forks a real Python process and blocks the calling thread until it
 * exits; calling any of it from the main thread freezes the UI for the entire
 * length of a download.
 */
object Ytdlp {

    private const val TAG = "slurp/engine"

    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()

    private val _initError = MutableStateFlow<String?>(null)
    val initError = _initError.asStateFlow()

    private val initLock = Mutex()

    /**
     * First call unpacks the Python runtime out of the APK, which takes a few
     * seconds on a cold start. Safe to call repeatedly — subsequent calls are
     * a no-op, and the lock keeps two callers from racing the unpack.
     */
    suspend fun ensureInit(context: Context) = withContext(Dispatchers.IO) {
        initLock.withLock {
            if (_ready.value) return@withLock
            try {
                val app = context.applicationContext
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                Aria2c.getInstance().init(app)
                _ready.value = true
                _initError.value = null
                Log.i(TAG, "engine ready, yt-dlp ${version(app) ?: "?"}")
            } catch (e: Throwable) {
                // Overwhelmingly this is an ABI mismatch: the installed split
                // does not match the device. Say so, because the raw exception
                // does not.
                _initError.value = describe(e)
                Log.e(TAG, "engine init failed", e)
            }
        }
    }

    fun version(context: Context): String? =
        runCatching { YoutubeDL.getInstance().version(context) }.getOrNull()

    /**
     * Asks yt-dlp what a URL actually is, without downloading anything.
     *
     * `--flat-playlist` is what keeps this fast: for a 200-video playlist it
     * lists the entries from the playlist page alone instead of resolving each
     * video, which would take minutes and hammer the site.
     */
    suspend fun probe(url: String): ProbeResult = withContext(Dispatchers.IO) {
        val req = YoutubeDLRequest(url)
        FormatPolicy.applyCommon(req)
        req.addOption("--dump-single-json")
        req.addOption("--flat-playlist")
        req.addOption("--skip-download")

        val raw = YoutubeDL.getInstance().execute(req).out
        val root = ProbeRoot.JSON.decodeFromString<ProbeRoot>(extractJson(raw))
        toResult(url, root)
    }

    private fun toResult(requestedUrl: String, root: ProbeRoot): ProbeResult {
        val entries = root.entries.orEmpty().mapNotNull { entry ->
            val entryUrl = entry.resolvedUrl() ?: return@mapNotNull null
            ProbeItem(
                url = entryUrl,
                title = entry.title?.takeIf { it.isNotBlank() } ?: entryUrl,
                durationSeconds = entry.duration?.toInt(),
                thumbnail = entry.thumbnail,
            )
        }

        // A "playlist" with one entry is just a video that happened to be
        // linked from a playlist page. Treat it as a single item so the UI does
        // not announce a batch of one.
        if (root.isPlaylist && entries.size > 1) {
            return ProbeResult(
                title = root.title?.takeIf { it.isNotBlank() } ?: "Playlist",
                isPlaylist = true,
                items = entries,
            )
        }

        val single = entries.firstOrNull() ?: ProbeItem(
            url = root.webpageUrl?.takeIf { it.startsWith("http") } ?: requestedUrl,
            title = root.title?.takeIf { it.isNotBlank() } ?: requestedUrl,
            durationSeconds = root.duration?.toInt(),
            thumbnail = root.thumbnail,
        )
        return ProbeResult(single.title, isPlaylist = false, items = listOf(single))
    }

    /**
     * Downloads one job into its own empty directory and returns the file that
     * appeared there.
     *
     * The private-directory-per-job trick is deliberate. yt-dlp's output
     * template supports enough variables that reconstructing the final path in
     * Kotlin is guesswork — extensions change after a merge, titles get
     * sanitised differently per platform, and `--extract-audio` renames the
     * file after the fact. Looking in an empty directory afterwards is exact.
     */
    suspend fun download(
        job: Job,
        workDir: File,
        onProgress: (progress: Float, etaSeconds: Long, line: String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        workDir.mkdirs()
        workDir.listFiles()?.forEach { it.delete() }

        val req = YoutubeDLRequest(job.url)
        FormatPolicy.applyCommon(req)
        FormatPolicy.apply(req, job.quality)

        // A YouTube link copied while watching a playlist carries both `v=` and
        // `list=`. Without this, one queued video quietly becomes 200.
        req.addOption("--no-playlist")
        req.addOption("-o", File(workDir, "%(title).150B [%(id)s].%(ext)s").absolutePath)

        YoutubeDL.getInstance().execute(req, job.processId) { percent, eta, line ->
            onProgress(if (percent < 0f) -1f else percent / 100f, eta, line)
        }

        workDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && it.length() > 0 }
            ?.maxByOrNull { it.length() }
            ?: error("yt-dlp finished without producing a file")
    }

    fun cancel(job: Job) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(job.processId) }
    }

    /**
     * Pulls a newer yt-dlp at runtime. This is the single most valuable thing
     * in the app: extractors break when sites change, and this fixes them
     * without shipping a new APK.
     */
    suspend fun updateEngine(context: Context): String = withContext(Dispatchers.IO) {
        try {
            val status = YoutubeDL.getInstance()
                .updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE)
            when (status?.name) {
                "DONE" -> "Updated to ${version(context) ?: "latest"}"
                "ALREADY_UP_TO_DATE" -> "Already current (${version(context) ?: "?"})"
                else -> "Update finished: ${status?.name ?: "unknown"}"
            }
        } catch (e: Throwable) {
            "Update failed: ${describe(e)}"
        }
    }

    /**
     * yt-dlp writes progress lines to stdout alongside the JSON document, and
     * some extractors print a cookie notice first. Slice from the first brace
     * to the last so a stray line does not fail the parse.
     */
    private fun extractJson(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "no JSON in yt-dlp output" }
        return raw.substring(start, end + 1)
    }

    fun describe(e: Throwable): String {
        val msg = e.message?.trim().orEmpty()
        if (msg.isEmpty()) return e::class.java.simpleName

        // yt-dlp errors arrive as a wall of Python traceback. The useful line
        // is the one it prefixes with ERROR:.
        val errorLine = msg.lineSequence().firstOrNull { it.contains("ERROR:") }
        val chosen = errorLine ?: msg.lineSequence().last { it.isNotBlank() }
        return chosen.substringAfter("ERROR:").trim().take(300)
    }
}
