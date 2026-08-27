package app.slurp.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates the app itself, by reading the GitHub releases feed.
 *
 * **`releases.atom`, not the API.** Unauthenticated `api.github.com` allows 60
 * requests an hour *per IP*, and an ISP that NATs its customers shares one
 * address between thousands of them — so a check can return 403 on a phone that
 * has never called GitHub in its life. That was measured on this project's
 * sibling, nyaarank: `/rate_limit` reported 0 of 60 remaining for an address
 * that had made no requests of its own. The atom feed is served by the web host
 * under no such quota.
 *
 * This is a different thing from [app.slurp.engine.Ytdlp.requestUpdate], which
 * replaces the bundled yt-dlp without touching the APK. That one fixes a site
 * that stopped working; this one ships new app code. Both are needed.
 */
object AppUpdater {

    private const val FEED = "https://github.com/Javieboy/slurp/releases.atom"
    private const val DOWNLOAD_BASE = "https://github.com/Javieboy/slurp/releases/download"

    /** The newest release's tag lands here first; release body HTML comes later. */
    private val TAG_RE = Regex("""/releases/tag/([^"'\s<]+)""")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val _available = MutableStateFlow<Update?>(null)
    val available = _available.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    data class Update(val tag: String, val version: String, val apkUrl: String)

    fun installedVersion(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0"

    fun check(context: Context) {
        if (_busy.value) return
        val app = context.applicationContext
        scope.launch {
            _busy.value = true
            try {
                val installed = installedVersion(app)
                val tag = fetchLatestTag()
                if (tag == null) {
                    _messages.emit("Could not read the release feed")
                    return@launch
                }
                val latest = tag.removePrefix("v")
                if (compareVersions(latest, installed) <= 0) {
                    _messages.emit("Already on the latest version ($installed)")
                    _available.value = null
                    return@launch
                }
                val url = resolveApkUrl(tag)
                if (url == null) {
                    // A release whose assets were named differently. Say so
                    // rather than handing the installer a 404 page.
                    _messages.emit("$latest is out, but no APK matched this device")
                    return@launch
                }
                _available.value = Update(tag, latest, url)
                _messages.emit("Version $latest is available")
            } catch (e: Throwable) {
                _messages.emit("Update check failed: ${e.message ?: e::class.java.simpleName}")
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * Downloads through [DownloadManager] rather than in-process: the APK is
     * ~80 MB, and an in-app download dies the moment Android decides to reclaim
     * the process. DownloadManager survives that and shows its own progress.
     */
    fun downloadAndInstall(context: Context, update: Update) {
        if (_busy.value) return
        val app = context.applicationContext
        scope.launch {
            _busy.value = true
            try {
                val file = File(
                    app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    "slurp-${update.version}.apk",
                )
                if (file.exists()) file.delete()

                val manager = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val id = manager.enqueue(
                    DownloadManager.Request(Uri.parse(update.apkUrl))
                        .setTitle("slurp ${update.version}")
                        .setDescription("Downloading update")
                        .setMimeType("application/vnd.android.package-archive")
                        .setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                        )
                        .setDestinationInExternalFilesDir(
                            app, Environment.DIRECTORY_DOWNLOADS, file.name,
                        )
                )

                _messages.emit("Downloading ${update.version}…")
                val ok = awaitDownload(manager, id)
                if (!ok) {
                    _messages.emit("Update download failed")
                    return@launch
                }
                launchInstaller(app, file)
            } catch (e: Throwable) {
                _messages.emit("Update failed: ${e.message ?: e::class.java.simpleName}")
            } finally {
                _busy.value = false
            }
        }
    }

    /** Polls rather than registering a receiver — one fewer manifest component. */
    private suspend fun awaitDownload(manager: DownloadManager, id: Long): Boolean {
        while (true) {
            val status = manager.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) return false
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            }
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> return true
                DownloadManager.STATUS_FAILED -> return false
                else -> delay(700)
            }
        }
    }

    private fun launchInstaller(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private suspend fun fetchLatestTag(): String? = withContext(Dispatchers.IO) {
        val body = get(FEED) ?: return@withContext null
        TAG_RE.find(body)?.groupValues?.getOrNull(1)
    }

    /**
     * The release ships per-ABI splits, so the right asset depends on the
     * device. A HEAD guards the derived URL; if the split is missing — only
     * arm64 and universal are published today — it falls back to the universal
     * APK, which runs anywhere.
     */
    private suspend fun resolveApkUrl(tag: String): String? = withContext(Dispatchers.IO) {
        val abi = Build.SUPPORTED_ABIS.firstOrNull()
        // Two naming schemes, and both have to stay here. Releases from 1.3.1
        // name assets for whoever is reading the releases page rather than for
        // Gradle; 1.3.0 and earlier used the raw Gradle output names and are
        // still installed on people's phones. Dropping the old names would
        // stand those installs up with "no APK matched this device".
        //
        // Per-ABI before universal in both schemes, so the ~85 MB build always
        // wins over the ~190 MB one when a release carries both.
        val candidates = listOfNotNull(
            abi?.let { "$DOWNLOAD_BASE/$tag/slurp-$it-recommended.apk" },
            abi?.let { "$DOWNLOAD_BASE/$tag/app-$it-release.apk" },
            "$DOWNLOAD_BASE/$tag/slurp-universal-fallback.apk",
            "$DOWNLOAD_BASE/$tag/app-universal-release.apk",
        )
        candidates.firstOrNull { exists(it) }
    }

    private fun exists(url: String): Boolean = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            conn.responseCode == 200
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)

    private fun get(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** Numeric compare of dotted versions; missing parts count as zero. */
    internal fun compareVersions(a: String, b: String): Int {
        val left = a.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val right = b.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(left.size, right.size)) {
            val d = (left.getOrNull(i) ?: 0) - (right.getOrNull(i) ?: 0)
            if (d != 0) return d
        }
        return 0
    }
}
