package app.slurp.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs arbitrary Python on the interpreter that youtubedl-android already
 * unpacked for yt-dlp.
 *
 * The library exposes no API for this — [com.yausername.youtubedl_android.YoutubeDL.execute]
 * only ever runs its own yt-dlp binary. But it does not need to. Every input
 * its `init()` uses is reachable from [Context], and the layout it builds is
 * fixed:
 *
 * ```
 * binDir      = applicationInfo.nativeLibraryDir      // libpython.so lives here
 * base        = noBackupFilesDir/youtubedl-android
 * packages    = base/packages                         // python/, ffmpeg/, aria2c/
 * ```
 *
 * so the same interpreter can be driven with a plain ProcessBuilder and the
 * same six environment variables. That is what makes a second Python engine a
 * feature rather than a fork of the library.
 *
 * **This does not initialise anything.** It reads a runtime that
 * [Ytdlp.ensureInit] must already have unpacked — call that first, or the
 * interpreter simply will not be on disk yet.
 */
object PythonRuntime {

    /**
     * Live processes by job id, so a job cancelled from the UI can actually
     * kill the Python it forked. Mirrors the library's own idProcessMap; ours
     * has to be separate because the library will not register a process it did
     * not start.
     */
    private val processes = ConcurrentHashMap<String, Process>()

    private fun binDir(context: Context) = File(context.applicationInfo.nativeLibraryDir)

    /** The interpreter itself. Shipped as a .so so the installer extracts it. */
    fun python(context: Context): File = File(binDir(context), "libpython.so")

    private fun packages(context: Context) =
        File(File(context.noBackupFilesDir, "youtubedl-android"), "packages")

    /** True once [Ytdlp.ensureInit] has unpacked the runtime this depends on. */
    fun ready(context: Context): Boolean =
        python(context).exists() && File(packages(context), "python/usr").exists()

    /**
     * The environment the interpreter needs, rebuilt exactly as the library
     * builds it. Getting any of these wrong produces a Python that starts and
     * then cannot find its own standard library or any CA certificate.
     */
    private fun environment(context: Context): Map<String, String> {
        val python = File(packages(context), "python").absolutePath
        val ffmpeg = File(packages(context), "ffmpeg").absolutePath
        val aria2c = File(packages(context), "aria2c").absolutePath
        return mapOf(
            "LD_LIBRARY_PATH" to "$python/usr/lib:$ffmpeg/usr/lib:$aria2c/usr/lib",
            "SSL_CERT_FILE" to "$python/usr/etc/tls/cert.pem",
            "PYTHONHOME" to "$python/usr",
            // The library sets HOME to PYTHONHOME too. Left alone, Python picks
            // a HOME that is not writable and some libraries fall over trying
            // to place a cache in it.
            "HOME" to "$python/usr",
            "TMPDIR" to context.cacheDir.absolutePath,
        )
    }

    /** What a finished run produced. [code] is the process exit status. */
    data class Result(val code: Int, val output: String) {
        val ok: Boolean get() = code == 0
    }

    /**
     * Runs the interpreter and blocks until it exits, feeding every output line
     * to [onLine] as it arrives.
     *
     * stderr is folded into stdout, as the library does: Python writes progress
     * and errors to both and interleaving them in order is what makes the last
     * line before a failure the useful one.
     *
     * @param pythonPath entries prepended to PYTHONPATH. Wheels are zips and
     * Python imports straight out of them, so these are usually .whl files.
     */
    suspend fun run(
        context: Context,
        args: List<String>,
        pythonPath: List<File> = emptyList(),
        processId: String? = null,
        onLine: (String) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val builder = ProcessBuilder(listOf(python(context).absolutePath) + args)
            .redirectErrorStream(true)

        builder.environment().apply {
            putAll(environment(context))
            if (pythonPath.isNotEmpty()) {
                this["PYTHONPATH"] = pythonPath.joinToString(":") { it.absolutePath }
            }
        }

        val process = builder.start()
        processId?.let { processes[it] = process }

        val collected = StringBuilder()
        try {
            process.inputStream.bufferedReader().use { reader: BufferedReader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    // Bounded: a long gallery run can print thousands of lines
                    // and the whole thing is only ever used for the error
                    // message at the end.
                    if (collected.length < MAX_OUTPUT) collected.append(line).append('\n')
                    onLine(line)
                }
            }
            Result(process.waitFor(), collected.toString())
        } finally {
            processId?.let { processes.remove(it) }
            process.destroy()
        }
    }

    /** Kills the process a job forked, if it is still running. */
    fun destroy(processId: String): Boolean {
        val process = processes.remove(processId) ?: return false
        return runCatching { process.destroy(); true }.getOrDefault(false)
    }

    private const val MAX_OUTPUT = 64 * 1024
}
