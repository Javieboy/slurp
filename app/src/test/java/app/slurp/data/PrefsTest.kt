package app.slurp.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins `sanitiseFolder`, which is the one piece of [Prefs] with any logic in it
 * and the one that can break every download at once.
 *
 * The failure it guards against is nastier than it looks. The folder name goes
 * into MediaStore's RELATIVE_PATH, and a value MediaStore will not accept fails
 * the insert at the *end* of a download that otherwise worked — bandwidth spent,
 * file gone, and an error that used to blame the disk being full. A name that
 * MediaStore *does* accept but the gallery hides is worse still: the download
 * reports success and the file is nowhere a person can find it.
 *
 * Pure JVM: the function is on the companion and touches nothing Android.
 */
class PrefsTest {

    @Test
    fun `an ordinary name is left alone`() {
        assertEquals("slurp", Prefs.sanitiseFolder("slurp"))
        assertEquals("My Videos", Prefs.sanitiseFolder("My Videos"))
    }

    @Test
    fun `surrounding whitespace goes`() {
        assertEquals("slurp", Prefs.sanitiseFolder("  slurp  "))
    }

    @Test
    fun `path separators and reserved characters go`() {
        assertEquals("ab", Prefs.sanitiseFolder("a/b"))
        assertEquals("ab", Prefs.sanitiseFolder("""a\b"""))
        assertEquals("ab", Prefs.sanitiseFolder("a:b"))
        assertEquals("ab", Prefs.sanitiseFolder("""a"b"""))
        assertEquals("ab", Prefs.sanitiseFolder("a<b"))
        assertEquals("ab", Prefs.sanitiseFolder("a|b"))
    }

    @Test
    fun `dot segments fall back to the default`() {
        // A dot segment in RELATIVE_PATH is refused outright, and the refusal
        // arrives at the end of a finished download.
        assertEquals(Prefs.DEFAULT_FOLDER, Prefs.sanitiseFolder(".."))
        assertEquals(Prefs.DEFAULT_FOLDER, Prefs.sanitiseFolder("."))
        assertEquals(Prefs.DEFAULT_FOLDER, Prefs.sanitiseFolder("..."))
    }

    @Test
    fun `a leading dot is stripped rather than kept`() {
        // ".slurp" inserts perfectly well and then hides the folder from the
        // gallery and the media scanner, so downloads look like they succeed
        // and cannot be found afterwards.
        assertEquals("secret", Prefs.sanitiseFolder(".secret"))
    }

    @Test
    fun `trailing dots and spaces go`() {
        assertEquals("folder", Prefs.sanitiseFolder("folder ."))
        assertEquals("folder", Prefs.sanitiseFolder("folder..."))
    }

    @Test
    fun `control characters go`() {
        // A long-press paste carries a newline more often than you would think.
        assertEquals("myfolder", Prefs.sanitiseFolder("my\nfolder"))
        assertEquals("myfolder", Prefs.sanitiseFolder("my\tfolder"))
    }

    @Test
    fun `empty and blank fall back to the default`() {
        assertEquals(Prefs.DEFAULT_FOLDER, Prefs.sanitiseFolder(""))
        assertEquals(Prefs.DEFAULT_FOLDER, Prefs.sanitiseFolder("   "))
        assertEquals(Prefs.DEFAULT_FOLDER, Prefs.sanitiseFolder("///"))
    }

    @Test
    fun `long names are truncated and not left ending on a dot`() {
        val long = "x".repeat(80)
        assertEquals(40, Prefs.sanitiseFolder(long).length)
        // Truncation can land on a dot, which is the trailing-dot case again.
        assertEquals("y".repeat(39), Prefs.sanitiseFolder("y".repeat(39) + "." + "z".repeat(20)))
    }
}
