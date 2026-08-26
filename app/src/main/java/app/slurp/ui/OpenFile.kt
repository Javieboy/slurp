package app.slurp.ui

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Opening a finished download, and the honest limits of doing so on Android.
 */
object OpenFile {

    /**
     * Plays the file in whatever the user's video or audio player is.
     *
     * The URI is a MediaStore content:// one, so the read grant has to travel
     * with the intent — the player is another process and has no rights to it
     * otherwise.
     */
    fun play(context: Context, uri: String, mime: String?): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uri), mime ?: "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    /**
     * Best effort at showing the containing folder.
     *
     * Android has no supported "reveal this file" intent. `ACTION_VIEW` on a
     * directory is not part of any contract — some file managers answer it,
     * many do not, and there is no way to ask in advance which. So this tries
     * the documented-ish routes in order and reports whether anything took it;
     * the caller falls back to simply telling the user the path, which is
     * always true and never crashes.
     */
    fun openFolder(context: Context, location: String): Boolean {
        val attempts = listOf(
            // Documents UI rooted at internal storage. Answered by the stock
            // Files app on most devices, though nothing guarantees it.
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.parse("content://com.android.externalstorage.documents/root/primary"),
                    DocumentsContract.Document.MIME_TYPE_DIR,
                )
            },
            // The system Downloads screen. Always present, and the right place
            // when the download root is set to Download.
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS),
        )
        for (intent in attempts) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
                // Try the next one.
            }
        }
        return false
    }

    /** Guessed from the saved file name, for the play intent. */
    fun mimeFor(name: String?, isAudio: Boolean): String = when {
        name == null -> if (isAudio) "audio/*" else "video/*"
        name.endsWith(".mp4", true) || name.endsWith(".m4v", true) -> "video/mp4"
        name.endsWith(".webm", true) -> "video/webm"
        name.endsWith(".mkv", true) -> "video/x-matroska"
        name.endsWith(".m4a", true) -> "audio/mp4"
        name.endsWith(".mp3", true) -> "audio/mpeg"
        name.endsWith(".opus", true) || name.endsWith(".ogg", true) -> "audio/ogg"
        else -> if (isAudio) "audio/*" else "video/*"
    }
}
