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
     * A pre-probe guess used only to label the button ("Download playlist").
     * The authoritative answer comes from yt-dlp's own `_type` field; this just
     * avoids a UI that says "Download" and then queues twelve things.
     */
    fun looksLikePlaylist(url: String): Boolean {
        val lower = url.lowercase()
        return "list=" in lower ||
            "/playlist" in lower ||
            "/sets/" in lower ||
            Regex("""/@[^/]+/(videos|shorts|streams)""").containsMatchIn(lower)
    }
}
