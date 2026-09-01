package app.slurp.download

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import app.slurp.data.Prefs
import java.io.File

/**
 * Moves a finished download out of app-private cache and into the shared
 * Movies/slurp or Music/slurp collection, so it shows up in the gallery and
 * survives the app being uninstalled.
 *
 * yt-dlp has to write to a real filesystem path, and scoped storage gives us a
 * content URI rather than a path, so this is a copy-then-delete rather than a
 * rename. On a large file that costs a second or two of disk churn; there is no
 * way around it short of requesting all-files access, which is not worth it.
 */
object MediaStoreSink {

    /** Where a finished file ended up, and how to open it again. */
    data class Saved(val name: String, val uri: String, val location: String)

    fun publish(context: Context, source: File, isAudio: Boolean, prefs: Prefs): Saved {
        val resolver = context.contentResolver
        val collection = if (isAudio) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        // Audio always goes to Music — that is where players and the system
        // media scanner look for it. Only video's collection is configurable.
        val root = if (isAudio) "Music" else prefs.videoRoot.directory
        val relative = "$root/${prefs.folderName}"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(source, isAudio))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            // Hides the entry from other apps until the bytes are actually
            // there. Without this the gallery briefly shows a broken thumbnail.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        // Naming only the likeliest cause was actively misleading: a folder name
        // MediaStore will not file under — a dot segment, a stray control
        // character — fails here too, at the end of a download that otherwise
        // worked, and "storage full?" sends people to clear space that was
        // never the problem. Prefs.sanitiseFolder now stops most of those at the
        // source; the message no longer guesses about the rest.
        val uri = resolver.insert(collection, values)
            ?: error("MediaStore would not file this under “$relative” (folder name, or no space left)")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out, DEFAULT_BUFFER_SIZE * 8) }
            } ?: error("could not open the destination for writing")

            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
        } catch (e: Throwable) {
            // A half-written pending entry would linger forever otherwise.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        source.delete()
        return Saved(name = source.name, uri = uri.toString(), location = relative)
    }

    /**
     * MediaStore validates the mime type against the collection and the
     * RELATIVE_PATH it is being filed under, and rejects the insert outright if
     * they disagree. `application/octet-stream` used to be the fallback here,
     * which meant any extension not on this list — .ts and .flv turn up from
     * live streams and older embeds — failed the insert after the file had
     * already downloaded. Falling back to the container the collection expects
     * is always accepted, and Android sniffs the real format on playback
     * anyway.
     */
    private fun mimeOf(file: File, isAudio: Boolean): String = when (file.extension.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "ts", "m2ts", "mts" -> "video/mp2t"
        "flv" -> "video/x-flv"
        "3gp", "3gpp" -> "video/3gpp"
        "avi" -> "video/x-msvideo"
        "m4a", "aac" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "opus", "ogg", "oga" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        else -> if (isAudio) "audio/mp4" else "video/mp4"
    }
}
