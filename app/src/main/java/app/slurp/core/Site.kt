package app.slurp.core

/**
 * The sites slurp advertises support for. This list is cosmetic — it drives the
 * badge on a job card and nothing else. yt-dlp handles well over a thousand
 * sites, so an unrecognised host is passed through as [OTHER] rather than
 * rejected. Refusing unknown hosts would be strictly worse: it would break
 * Reddit, Twitch clips and every short-link redirector for no benefit.
 */
enum class Site(val label: String, val hosts: List<String>) {
    YOUTUBE("YouTube", listOf("youtube.com", "youtu.be", "music.youtube.com")),
    TIKTOK("TikTok", listOf("tiktok.com")),
    INSTAGRAM("Instagram", listOf("instagram.com", "instagr.am", "cdninstagram.com")),
    FACEBOOK("Facebook", listOf("facebook.com", "fb.watch", "fb.com")),
    THREADS("Threads", listOf("threads.net", "threads.com")),
    X("X", listOf("x.com", "twitter.com")),
    OTHER("Link", emptyList());

    companion object {
        fun of(url: String): Site {
            val host = UrlSniffer.hostOf(url) ?: return OTHER
            return entries.firstOrNull { site ->
                site.hosts.any { host == it || host.endsWith(".$it") }
            } ?: OTHER
        }
    }
}
