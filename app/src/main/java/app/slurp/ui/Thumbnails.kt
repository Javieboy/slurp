package app.slurp.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * A very small thumbnail loader.
 *
 * Coil would do this in one line, but the project keeps its dependency list
 * short on purpose — the APK is already ~85 MB per ABI split from the Python
 * runtime, ~190 MB universal — and a
 * card thumbnail needs almost none of what an image library provides. No disk
 * cache, no transformations, no placeholders beyond "nothing yet".
 *
 * Downsampled on decode rather than after: a YouTube poster is 1280 wide and
 * the card shows it at about 120dp, so decoding it at full size would spend
 * several megabytes per row to throw most of it away.
 */
object Thumbnails {

    private const val TARGET_WIDTH = 320

    private val cache = LinkedHashMap<String, Bitmap>(
        /* initialCapacity = */ 16, /* loadFactor = */ 0.75f, /* accessOrder = */ true,
    )
    private val lock = Mutex()

    /** Bounded so a long playlist cannot grow this without limit. */
    private const val MAX_ENTRIES = 40

    suspend fun get(url: String): Bitmap? = withContext(Dispatchers.IO) {
        lock.withLock { cache[url] }?.let { return@withContext it }

        val bitmap = runCatching { fetch(url) }.getOrNull() ?: return@withContext null

        lock.withLock {
            cache[url] = bitmap
            while (cache.size > MAX_ENTRIES) {
                val oldest = cache.keys.firstOrNull() ?: break
                cache.remove(oldest)
            }
        }
        bitmap
    }

    private fun fetch(url: String): Bitmap? {
        val bytes = open(url)?.use { it.readBytes() } ?: return null

        // First pass reads only the header, to learn the real dimensions.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        var sample = 1
        while (bounds.outWidth / sample > TARGET_WIDTH * 2) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun open(url: String) = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        if (conn.responseCode != 200) {
            conn.disconnect()
            null
        } else {
            conn.inputStream
        }
    }.getOrNull()
}

/**
 * Loads [url] and returns it once it arrives, null until then.
 *
 * Keyed on the URL so recomposition does not refetch, and so a recycled card
 * showing a different job starts a new load rather than keeping the old image.
 */
@Composable
fun rememberThumbnail(url: String?): Bitmap? {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        if (url != null) bitmap = Thumbnails.get(url)
    }
    return bitmap
}
