package app.slurp.engine

import app.slurp.model.Quality
import com.yausername.youtubedl_android.YoutubeDLRequest

/**
 * Translates a [Quality] into yt-dlp format selectors.
 *
 * The `<=?` operator matters: plain `height<=1080` makes the whole selector
 * fail when a site reports no height at all, which is the normal case for
 * TikTok, Threads and most X videos. The `?` makes the constraint advisory, so
 * a video with unknown height is still eligible. Every selector then falls back
 * through progressively looser alternatives to a bare `b` (best single file),
 * because the six target sites mostly serve one pre-muxed mp4 and have nothing
 * to merge.
 */
object FormatPolicy {

    fun apply(request: YoutubeDLRequest, quality: Quality) {
        if (quality.isAudio) {
            request.addOption("-f", "ba/b")
            request.addOption("-x")
            request.addOption("--audio-format", "m4a")
            request.addOption("--audio-quality", "0")
            return
        }

        request.addOption("-f", videoSelector(quality))
        // Merging only happens when a separate video and audio stream were
        // chosen. Forcing mp4 keeps the result playable in Android's gallery,
        // which will not open a .mkv.
        request.addOption("--merge-output-format", "mp4")
    }

    private fun videoSelector(quality: Quality): String = when (quality) {
        Quality.BEST -> "bv*+ba/b"
        Quality.P1080 -> "bv*[height<=?1080]+ba/b[height<=?1080]/bv*+ba/b"
        Quality.P720 -> "bv*[height<=?720]+ba/b[height<=?720]/bv*+ba/b"
        // Unreachable — [apply] returns before this for audio. Present only to
        // keep the `when` exhaustive, so audio's real selector is the one in
        // [apply] and editing this line changes nothing.
        Quality.AUDIO -> "ba/b"
    }

    /** Options every request gets, download or probe. */
    fun applyCommon(request: YoutubeDLRequest) {
        request.addOption("--no-warnings")
        request.addOption("--no-playlist-reverse")
        // Three retries on the socket, and skip the fragment rather than dying
        // on it — Facebook and Instagram drop fragments fairly often.
        request.addOption("-R", "3")
        request.addOption("--fragment-retries", "3")
        // Keep mtime off: yt-dlp otherwise stamps files with the upload date,
        // which sorts them oddly in the gallery.
        request.addOption("--no-mtime")
    }
}
