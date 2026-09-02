package app.slurp.data

import android.content.Context
import app.slurp.model.Quality

/**
 * Plain SharedPreferences. Nothing here is a secret — no tokens, no cookies —
 * so there is no reason to reach for EncryptedSharedPreferences and its
 * dependency.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("slurp", Context.MODE_PRIVATE)

    var quality: Quality
        get() = runCatching { Quality.valueOf(sp.getString(KEY_QUALITY, null) ?: "") }
            .getOrDefault(Quality.P1080)
        set(value) = sp.edit().putString(KEY_QUALITY, value.name).apply()

    /**
     * When true, a link arriving via the share sheet starts downloading with no
     * confirmation. This is the "instantly downloads it" behaviour and it is on
     * by default; the format picker is still there for when it matters.
     */
    var oneTap: Boolean
        get() = sp.getBoolean(KEY_ONE_TAP, true)
        set(value) = sp.edit().putBoolean(KEY_ONE_TAP, value).apply()

    /**
     * Last time the bundled yt-dlp was updated, epoch millis.
     *
     * Read by the queue's automatic recovery as a cooldown, so a run of failing
     * jobs cannot trigger an engine update each.
     */
    var lastEngineUpdate: Long
        get() = sp.getLong(KEY_LAST_UPDATE, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_UPDATE, value).apply()

    /**
     * Folder name under the media collection — `Movies/<this>` for video,
     * `Music/<this>` for audio.
     *
     * Scoped storage is why this is a folder *name* and not a path. MediaStore
     * only accepts a RELATIVE_PATH under a standard collection, so an arbitrary
     * directory would mean going through SAF and giving up the automatic
     * gallery listing. Blank falls back to the default rather than writing to
     * the collection root.
     */
    var folderName: String
        // Sanitised on the way out as well as in. The setter has not always
        // cleaned as thoroughly as it does now, and a value stored by an older
        // build must not be able to break every save.
        get() = sanitiseFolder(sp.getString(KEY_FOLDER, null).orEmpty())
        set(value) {
            val cleaned = sanitiseFolder(value)
            sp.edit().putString(KEY_FOLDER, cleaned).apply()
        }

    /** Last automatic check of the release feed, epoch millis. Rate-limits the launch check. */
    var lastUpdateCheck: Long
        get() = sp.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()

    /** Where video lands. Audio always goes to `Music`, which is where players look. */
    var videoRoot: VideoRoot
        get() = runCatching { VideoRoot.valueOf(sp.getString(KEY_VIDEO_ROOT, null) ?: "") }
            .getOrDefault(VideoRoot.MOVIES)
        set(value) = sp.edit().putString(KEY_VIDEO_ROOT, value.name).apply()

    companion object {
        const val DEFAULT_FOLDER = "slurp"

        /**
         * MediaStore rejects a RELATIVE_PATH containing path separators it did
         * not expect, and silently mangles some punctuation, so the name is
         * reduced to something that always survives the insert.
         *
         * Three cases the first version of this missed, all of them reachable
         * by typing into the Settings field:
         *
         * - `..` and `.` came through untouched. A dot segment in a
         *   RELATIVE_PATH is refused outright, and the refusal lands at the
         *   *end* of a finished download, reported as though the disk were
         *   full.
         * - A leading dot (`.slurp`) inserts fine and then hides the folder
         *   from the gallery and the media scanner, so downloads appear to
         *   succeed and are nowhere to be found. That is worse than any name
         *   substituted for it.
         * - Control characters survived, and a long-press paste carries a
         *   newline more often than you would think.
         */
        fun sanitiseFolder(raw: String): String =
            raw.replace(Regex("""[\\/:*?"<>|]"""), "")
                .replace(Regex("""\p{Cntrl}"""), "")
                // Leading and trailing dots and spaces, both before and after
                // the truncation — cutting at 40 can land on one.
                .trim()
                .trim('.', ' ')
                .take(40)
                .trim('.', ' ')
                .ifEmpty { DEFAULT_FOLDER }

        private const val KEY_QUALITY = "quality"
        private const val KEY_ONE_TAP = "one_tap"
        private const val KEY_LAST_UPDATE = "last_engine_update"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_FOLDER = "folder_name"
        private const val KEY_VIDEO_ROOT = "video_root"
    }
}

/**
 * Where video lands.
 *
 * [MOVIES] and [DCIM] are directories the MediaStore *Video* collection
 * accepts (so is Pictures, which is not offered because filing video there is
 * odd). [DOWNLOAD] is not — it is refused by that collection — so
 * `MediaStoreSink` files it through the Downloads collection instead. The
 * consequence is visible to the user: video in Download shows up in Files
 * rather than in the gallery, which is why the default is Movies.
 */
enum class VideoRoot(val label: String, val directory: String) {
    MOVIES("Movies", "Movies"),
    DCIM("DCIM", "DCIM"),
    DOWNLOAD("Download", "Download"),
}
