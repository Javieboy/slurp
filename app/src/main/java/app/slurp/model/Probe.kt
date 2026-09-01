package app.slurp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * yt-dlp's `--dump-single-json` output, cut down to the handful of fields the
 * UI actually shows. The real document has well over a hundred keys and its
 * shape varies per extractor, so [JSON] is configured to ignore everything it
 * does not recognise — without that, one unexpected key from one site takes
 * down the whole probe.
 */
@Serializable
data class ProbeEntry(
    val id: String? = null,
    val title: String? = null,
    val url: String? = null,
    @SerialName("webpage_url") val webpageUrl: String? = null,
    val duration: Double? = null,
    val thumbnail: String? = null,
    val uploader: String? = null,
    val ext: String? = null,
    @SerialName("filesize_approx") val filesizeApprox: Long? = null,
) {
    /**
     * With `--flat-playlist`, entries carry `url` as the canonical page link.
     * Without it, `webpage_url` is the reliable one. Prefer whichever is a real
     * http link; a bare video id is useless to re-feed to yt-dlp.
     */
    fun resolvedUrl(): String? =
        listOf(webpageUrl, url).firstOrNull { it != null && it.startsWith("http") }
}

@Serializable
data class ProbeRoot(
    @SerialName("_type") val type: String? = null,
    val id: String? = null,
    val title: String? = null,
    @SerialName("webpage_url") val webpageUrl: String? = null,
    val uploader: String? = null,
    val thumbnail: String? = null,
    val duration: Double? = null,
    val ext: String? = null,
    @SerialName("filesize_approx") val filesizeApprox: Long? = null,
    val entries: List<ProbeEntry>? = null,
) {
    val isPlaylist: Boolean get() = type == "playlist" || type == "multi_video" || entries != null

    companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }
}

/** What a probe turns into: one or more concrete things to download. */
data class ProbeResult(
    val title: String,
    val isPlaylist: Boolean,
    val items: List<ProbeItem>,
    /**
     * Entries yt-dlp listed but gave no usable link for, and which are
     * therefore not in [items]. Surfaced rather than swallowed: a playlist that
     * queues 37 of 40 videos should say so.
     */
    val skipped: Int = 0,
)

data class ProbeItem(
    val url: String,
    val title: String,
    val durationSeconds: Int?,
    val thumbnail: String?,
)
