package app.slurp.core

import java.net.URI

/**
 * Pulls a usable URL out of whatever the share sheet hands us.
 *
 * This is less trivial than it looks. Sharing from TikTok produces something
 * like "Check this out! https://vt.tiktok.com/ZSxxxx/ - watch it on TikTok",
 * Instagram wraps the link in its own caption text, and several apps append a
 * trailing period or a zero-width character. Feeding any of that straight to
 * yt-dlp fails with an unhelpful "Unsupported URL".
 */
object UrlSniffer {

    private val URL_RE = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    /** Characters that are legal in a URL but are almost always sentence punctuation here. */
    private const val TRAILING_JUNK = ".,;:!?)]}'\"​‎‏"

    fun firstUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = URL_RE.find(text) ?: return null
        return clean(match.value)
    }

    /** Every URL in the text, de-duplicated, in order. Used for multi-link pastes. */
    fun allUrls(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return URL_RE.findAll(text).map { clean(it.value) }.distinct().toList()
    }

    private fun clean(raw: String): String {
        var url = raw.trim()
        while (url.isNotEmpty() && TRAILING_JUNK.indexOf(url.last()) >= 0) {
            // Only strip a closing bracket if it is unbalanced — some CDN URLs
            // legitimately end in one.
            val last = url.last()
            if ((last == ')' || last == ']' || last == '}') &&
                url.count { it == last } <= url.count { it == openerFor(last) }
            ) break
            url = url.dropLast(1)
        }
        return url
    }

    private fun openerFor(c: Char) = when (c) {
        ')' -> '('
        ']' -> '['
        '}' -> '{'
        else -> c
    }

    fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase()?.removePrefix("www.")?.removePrefix("m.")
    } catch (_: Exception) {
        null
    }

    fun isProbablyUrl(text: String): Boolean = URL_RE.containsMatchIn(text)

    /**
     * True when the link names one specific video that merely *happens* to sit
     * inside a playlist, rather than naming the playlist itself.
     *
     * This exists because of a real trap. Share anything from the YouTube app
     * while watching from a playlist or an autoplay Mix and the URL carries
     * both `v=` and `list=`. `download()` passes `--no-playlist`, but the probe
     * runs first and did not, so the playlist was expanded into one job per
     * entry before that flag could matter. Measured, not guessed: probing one
     * ordinary shared link this way returned `_type: playlist` with **279**
     * entries — 279 queued jobs, run one at a time, in a queue that does not
     * survive the app being killed.
     *
     * A bare `/playlist?list=…` is the playlist itself and still expands, which
     * is the behaviour the README describes and people actually want.
     */
    fun namesOneVideoInsideAPlaylist(url: String): Boolean {
        val lower = url.lowercase()
        if ("list=" !in lower) return false
        // The playlist page itself — expand this one.
        if ("/playlist" in lower) return false
        // Either the ?v= form or a youtu.be short link names a single video.
        return Regex("""[?&]v=""").containsMatchIn(lower) || "youtu.be/" in lower
    }
}
