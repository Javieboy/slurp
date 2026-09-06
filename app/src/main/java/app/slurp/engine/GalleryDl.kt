package app.slurp.engine

import android.content.Context
import android.util.Log
import app.slurp.model.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The image half of slurp, on top of gallery-dl.
 *
 * yt-dlp is a *video* downloader. An Instagram photo post or an X image tweet
 * has no video in it, so yt-dlp refuses rather than returning something, and no
 * engine update ever fixes that — it is scope, not breakage. gallery-dl is the
 * tool for exactly those links, and it is pure Python, which matters because
 * slurp already ships a Python interpreter for yt-dlp. [PythonRuntime] drives
 * it; nothing here needs a fork of youtubedl-android.
 *
 * **Nothing is bundled, and that is a licence requirement rather than a
 * preference.** gallery-dl is GPL-2.0-*only* — its per-file grant reads
 * "version 2 as published by the Free Software Foundation" with no "or any
 * later version" — and slurp is GPLv3, which it inherits from
 * youtubedl-android's POM and the ffmpeg build inside the APK. Those two
 * licences are incompatible, so an APK containing both could not lawfully be
 * distributed. GPL obligations attach to *distribution*: slurp ships none of
 * it, and instead fetches the official wheels from PyPI onto the device on
 * first use. What lands is byte-for-byte what upstream published.
 *
 * Wheels are zips and Python imports straight out of them, so there is nothing
 * to unpack or assemble — the six files go on PYTHONPATH as they are, about
 * 1.4 MB in total, which is less than half the yt-dlp zipapp that
 * **Update engine** already downloads.
 */
object GalleryDl {

    private const val TAG = "slurp/gallery"

    /**
     * gallery-dl plus its runtime dependencies. requests pulls the other four,
     * and all six publish pure-Python `py3-none-any` wheels — which is the only
     * reason this works at all, since a compiled extension could not run on the
     * bundled interpreter.
     */
    private val PACKAGES = listOf(
        "gallery-dl", "requests", "urllib3", "certifi", "idna", "charset-normalizer",
    )

    private val installLock = Mutex()

    private val _installing = MutableStateFlow(false)
    val installing = _installing.asStateFlow()

    private fun home(context: Context) = File(context.noBackupFilesDir, "gallery-dl")

    private fun wheels(context: Context): List<File> =
        home(context).listFiles()?.filter { it.name.endsWith(".whl") }?.sorted().orEmpty()

    /** True once every package has a wheel on disk. */
    fun ready(context: Context): Boolean = wheels(context).size >= PACKAGES.size

    /**
     * Fetches the wheels if they are not already there. Safe to call often —
     * the lock keeps two links from racing the same download, and a complete
     * install returns immediately.
     *
     * @return null on success, or a description of what went wrong.
     */
    suspend fun ensureInstalled(context: Context): String? = installLock.withLock {
        if (ready(context)) return null
        _installing.value = true
        try {
            withContext(Dispatchers.IO) {
                val dir = home(context).apply { mkdirs() }
                for (pkg in PACKAGES) {
                    if (dir.listFiles()?.any { it.name.startsWith(wheelPrefix(pkg)) } == true) continue
                    val (name, url) = resolveWheel(pkg)
                        ?: return@withContext "PyPI did not offer a wheel for $pkg"
                    // Download beside the target and rename, so an interrupted
                    // fetch cannot leave a half wheel that imports as garbage.
                    val tmp = File(dir, "$name.part")
                    if (!download(url, tmp)) {
                        tmp.delete()
                        return@withContext "Could not download $name"
                    }
                    if (!tmp.renameTo(File(dir, name))) {
                        tmp.delete()
                        return@withContext "Could not store $name"
                    }
                    Log.i(TAG, "installed $name")
                }
                null
            }
        } finally {
            _installing.value = false
        }
    }

    /** Wheel filenames are `<name with underscores>-<version>-py3-none-any.whl`. */
    private fun wheelPrefix(pkg: String) = pkg.replace('-', '_') + "-"

    /**
     * Asks PyPI for the newest pure-Python wheel of [pkg].
     *
     * Resolved at runtime rather than pinned so that "Update image engine" is
     * the same one-line operation as updating yt-dlp: delete and refetch.
     */
    private fun resolveWheel(pkg: String): Pair<String, String>? = runCatching {
        val body = get("https://pypi.org/pypi/$pkg/json") ?: return null
        val urls = JSONObject(body).getJSONArray("urls")
        for (i in 0 until urls.length()) {
            val entry = urls.getJSONObject(i)
            val name = entry.getString("filename")
            // py3-none-any is the portable wheel. Anything else is compiled for
            // a platform that is not this one.
            if (name.endsWith("-py3-none-any.whl")) return name to entry.getString("url")
        }
        null
    }.getOrNull()

    private fun download(url: String, target: File): Boolean = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
        }
        try {
            if (conn.responseCode != 200) return false
            conn.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            true
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)

    private fun get(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 20_000
        }
        try {
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** The installed gallery-dl version, or null before it is installed. */
    suspend fun version(context: Context): String? {
        if (!ready(context)) return null
        val result = runCatching {
            PythonRuntime.run(context, listOf("-m", "gallery_dl", "--version"), wheels(context))
        }.getOrNull() ?: return null
        return result.output.trim().lines().firstOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * Downloads everything [job]'s link points at into its own empty directory,
     * and returns the files that appeared.
     *
     * A list rather than one file, because this is the case yt-dlp never has: a
     * carousel post is one link and several images, and they all belong to the
     * one card the user is looking at.
     *
     * `-D` writes into an exact directory, which is the same
     * empty-directory-afterwards trick `Ytdlp.download` uses and for the same
     * reason: reconstructing gallery-dl's own naming rules in Kotlin would be
     * guesswork.
     */
    suspend fun download(
        context: Context,
        job: Job,
        workDir: File,
        onLine: (String) -> Unit,
    ): List<File> {
        workDir.mkdirs()
        workDir.listFiles()?.forEach { it.deleteRecursively() }

        val result = PythonRuntime.run(
            context = context,
            args = listOf(
                "-m", "gallery_dl",
                // Everything into one flat directory rather than gallery-dl's
                // default site/user/ tree, which slurp would only have to walk
                // back out of again.
                "--directory", workDir.absolutePath,
                "--no-part",
                // gallery-dl retries hard by default; slurp would rather report
                // a failure than sit on a wedged link for minutes.
                "--retries", "3",
                job.url,
            ),
            pythonPath = wheels(context),
            processId = job.processId,
            onLine = onLine,
        )

        val files = workDir.walkTopDown()
            .filter { it.isFile && it.length() > 0 && !it.name.endsWith(".part") }
            .sortedBy { it.name }
            .toList()

        if (files.isEmpty()) {
            error(describe(result.output).ifBlank { "gallery-dl produced no files" })
        }
        return files
    }

    fun cancel(job: Job) {
        PythonRuntime.destroy(job.processId)
    }

    /**
     * Digs the useful line out of gallery-dl's output.
     *
     * Its errors do not look like yt-dlp's: they are one-liners prefixed with
     * the level and the extractor, so [Ytdlp.describe]'s "find the ERROR: line
     * in a Python traceback" does not apply.
     */
    fun describe(output: String): String {
        val error = output.lineSequence().lastOrNull { it.trimStart().startsWith("[error]") }
            ?: output.lineSequence().lastOrNull { it.contains("Error", ignoreCase = true) }
            ?: output.lineSequence().lastOrNull { it.isNotBlank() }
            ?: return ""
        return error.substringAfter("[error]").trim().take(300)
    }

    /**
     * Whether a yt-dlp failure is worth handing to gallery-dl.
     *
     * These are the ways yt-dlp says "there is nothing here I download",
     * as opposed to "this broke". An unsupported URL counts too: gallery-dl
     * knows about a thousand sites yt-dlp has never heard of.
     */
    fun worthTrying(error: String): Boolean {
        val lower = error.lowercase()
        return "no video" in lower ||
            "no media" in lower ||
            "unsupported url" in lower ||
            "no suitable" in lower ||
            "there is no" in lower
    }
}
