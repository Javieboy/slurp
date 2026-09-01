package app.slurp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the sniffer's behaviour against share-sheet noise.
 *
 * A caveat that matters more than the tests do: `CLAUDE.md` asks for this
 * parser to be checked against *real* shares from all six apps, and it has not
 * been. The app itself has run on a device, but the share sheet is one of the
 * paths that run did not exercise, so nothing here came off a real clipboard —
 * the strings are modelled on the shapes the README and UrlSniffer's own
 * comments describe. A green run proves the parser is internally consistent and
 * guards against regressions; it does not prove it survives what TikTok
 * actually sends. Replace these with captured shares when the share sheet gets
 * exercised.
 *
 * Pure JVM: UrlSniffer touches only kotlin.text and java.net.URI, so no
 * Robolectric and no Android stubs are involved.
 */
class UrlSnifferTest {

    @Test
    fun `bare url passes through untouched`() {
        val url = "https://youtu.be/dQw4w9WgXcQ"
        assertEquals(url, UrlSniffer.firstUrl(url))
    }

    @Test
    fun `pulls the link out of a caption`() {
        val shared = "Check this out! https://vt.tiktok.com/ZSabcdef/ - watch it on TikTok"
        assertEquals("https://vt.tiktok.com/ZSabcdef/", UrlSniffer.firstUrl(shared))
    }

    @Test
    fun `keeps query strings intact`() {
        val url = "https://x.com/user/status/1234567890?s=20"
        assertEquals(url, UrlSniffer.firstUrl(url))
    }

    @Test
    fun `strips trailing sentence punctuation`() {
        assertEquals("https://youtu.be/abc123", UrlSniffer.firstUrl("watch https://youtu.be/abc123."))
        assertEquals("https://youtu.be/abc123", UrlSniffer.firstUrl("watch https://youtu.be/abc123,"))
        assertEquals("https://youtu.be/abc123", UrlSniffer.firstUrl("watch https://youtu.be/abc123!?"))
    }

    @Test
    fun `strips an unbalanced closing bracket`() {
        assertEquals(
            "https://x.com/user/status/123",
            UrlSniffer.firstUrl("(see https://x.com/user/status/123)"),
        )
    }

    @Test
    fun `keeps a balanced closing bracket`() {
        // The case the trailing-bracket logic exists for: some CDN URLs really
        // do end in a bracket, and stripping it produces a 404 rather than an
        // obvious parse failure.
        val url = "https://cdn.example.com/clip_(1)"
        assertEquals(url, UrlSniffer.firstUrl(url))
    }

    @Test
    fun `stops at angle brackets and quotes`() {
        assertEquals("https://a.com/1", UrlSniffer.firstUrl("<https://a.com/1>"))
        assertEquals("https://a.com/1", UrlSniffer.firstUrl("\"https://a.com/1\""))
    }

    @Test
    fun `strips a trailing zero-width space`() {
        // Kotlin's \s does not match U+200B, so the regex swallows it into the
        // URL and only TRAILING_JUNK takes it back off.
        assertEquals(
            "https://www.instagram.com/reel/ABC123/",
            UrlSniffer.firstUrl("https://www.instagram.com/reel/ABC123/\u200B"),
        )
    }

    @Test
    fun `strips trailing directional marks`() {
        assertEquals("https://a.com/1", UrlSniffer.firstUrl("https://a.com/1\u200E"))
        assertEquals("https://a.com/1", UrlSniffer.firstUrl("https://a.com/1\u200F"))
    }

    @Test
    fun `a zero-width space inside the url survives - known gap`() {
        // Documents current behaviour, not desired behaviour. Only trailing
        // invisibles are stripped, so an embedded one reaches yt-dlp and fails
        // there with an unhelpful "Unsupported URL". Change this test when the
        // sniffer learns to strip them mid-string.
        val dirty = "https://vt.tiktok.com/ZS\u200Babcdef/"
        assertEquals(dirty, UrlSniffer.firstUrl(dirty))
    }

    @Test
    fun `no link means null`() {
        assertNull(UrlSniffer.firstUrl(null))
        assertNull(UrlSniffer.firstUrl(""))
        assertNull(UrlSniffer.firstUrl("   "))
        assertNull(UrlSniffer.firstUrl("just a caption with no link in it"))
    }

    @Test
    fun `allUrls keeps order and drops duplicates`() {
        val text = "one https://a.com/1 two https://b.com/2 again https://a.com/1"
        assertEquals(listOf("https://a.com/1", "https://b.com/2"), UrlSniffer.allUrls(text))
    }

    @Test
    fun `allUrls is empty for nothing`() {
        assertEquals(emptyList<String>(), UrlSniffer.allUrls(null))
        assertEquals(emptyList<String>(), UrlSniffer.allUrls("no links"))
    }

    @Test
    fun `hostOf normalises www and m prefixes`() {
        assertEquals("youtube.com", UrlSniffer.hostOf("https://www.youtube.com/watch?v=abc"))
        assertEquals("facebook.com", UrlSniffer.hostOf("https://m.facebook.com/watch/?v=1"))
        assertEquals("vt.tiktok.com", UrlSniffer.hostOf("https://vt.tiktok.com/ZSabcdef/"))
    }

    @Test
    fun `hostOf returns null for junk`() {
        assertNull(UrlSniffer.hostOf("not a url at all"))
    }

    @Test
    fun `isProbablyUrl needs a scheme`() {
        assertTrue(UrlSniffer.isProbablyUrl("go to https://x.com/a now"))
        assertFalse(UrlSniffer.isProbablyUrl("x.com/a"))
    }

    // namesOneVideoInsideAPlaylist replaced looksLikePlaylist and inverted the
    // question: true now means "this is ONE video that merely sits inside a
    // playlist, so do not expand it", which is the 279-job trap from the commit
    // that introduced it.

    @Test
    fun `a video shared from inside a playlist is one video`() {
        assertTrue(UrlSniffer.namesOneVideoInsideAPlaylist(
            "https://www.youtube.com/watch?v=abc123&list=PLxyz"))
    }

    @Test
    fun `an autoplay Mix is one video`() {
        // The measured case: a Mix link probed as a 279-entry playlist.
        assertTrue(UrlSniffer.namesOneVideoInsideAPlaylist(
            "https://www.youtube.com/watch?v=abc123&list=RDabc123"))
    }

    @Test
    fun `a youtu be short link carrying a list is one video`() {
        assertTrue(UrlSniffer.namesOneVideoInsideAPlaylist(
            "https://youtu.be/abc123?list=PLxyz"))
    }

    @Test
    fun `the playlist page itself still expands`() {
        assertFalse(UrlSniffer.namesOneVideoInsideAPlaylist(
            "https://www.youtube.com/playlist?list=PLxyz"))
    }

    @Test
    fun `a link with no list is not a video inside a playlist`() {
        assertFalse(UrlSniffer.namesOneVideoInsideAPlaylist("https://youtu.be/dQw4w9WgXcQ"))
        assertFalse(UrlSniffer.namesOneVideoInsideAPlaylist("https://www.youtube.com/watch?v=abc123"))
        assertFalse(UrlSniffer.namesOneVideoInsideAPlaylist("https://vt.tiktok.com/ZSabcdef/"))
        assertFalse(UrlSniffer.namesOneVideoInsideAPlaylist("https://soundcloud.com/artist/sets/an-album"))
    }

    @Test
    fun `shorts, live and embed inside a playlist are one video too`() {
        // These three carry a list= exactly like /watch does, but name no `v=`
        // parameter, so they used to fall through and expand the whole list.
        assertTrue(UrlSniffer.namesOneVideoInsideAPlaylist(
            "https://www.youtube.com/shorts/abc123?list=PLxyz"))
        assertTrue(UrlSniffer.namesOneVideoInsideAPlaylist(
            "https://www.youtube.com/live/abc123?list=PLxyz"))
        assertTrue(UrlSniffer.namesOneVideoInsideAPlaylist(
            "https://www.youtube.com/embed/abc123?list=PLxyz"))
    }

    @Test
    fun `youtube music counts as youtube`() {
        assertTrue(UrlSniffer.namesOneVideoInsideAPlaylist(
            "https://music.youtube.com/watch?v=abc123&list=OLAK5uy_abc"))
    }

    @Test
    fun `the rule does not apply off youtube`() {
        // It was measured on YouTube and nowhere else, and the checks are too
        // loose to run against the other ~1800 sites yt-dlp handles. Answering
        // false here means expanding, which is the recoverable mistake; a false
        // true silently discards every entry but the first.
        assertFalse(UrlSniffer.namesOneVideoInsideAPlaylist(
            "https://example.com/album?list=1&v=2"))
        assertFalse(UrlSniffer.namesOneVideoInsideAPlaylist(
            "https://bandcamp.com/x?tracklist=1&v=2"))
    }

    @Test
    fun `list must be a real query parameter`() {
        // "checklist=" and "waitlist=" both contain "list=". The unanchored
        // substring match used to accept them.
        assertFalse(UrlSniffer.namesOneVideoInsideAPlaylist(
            "https://www.youtube.com/watch?v=abc123&checklist=1"))
    }
}
