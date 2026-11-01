package fi.pocketsk.install

import fi.pocketsk.install.InstallHistory.Entry
import fi.pocketsk.install.InstallHistory.Outcome
import fi.pocketsk.install.VersionChoices.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The versions the Updates card and the wizard list, and what each says. */
class VersionChoicesTest {
    private val available = listOf("2.33.0", "2.35.1", "2.36.0-beta.1", "2.34.0", "2.3.0-canboatv2.1")
        .mapNotNull(ServerVersion::parse)
    private val none = InstallHistory()

    private fun texts(choices: List<VersionChoices.Choice>) = choices.map { it.version.text }

    @Test
    fun releasesAloneNewestFirstUnlessAskedForTheRest() {
        assertEquals(listOf("2.35.1", "2.34.0", "2.33.0"), texts(VersionChoices.list(available, false, null, null, none)))
        assertEquals(
            listOf("2.36.0-beta.1", "2.35.1", "2.34.0", "2.33.0", "2.3.0-canboatv2.1"),
            texts(VersionChoices.list(available, true, null, null, none)),
        )
    }

    @Test
    fun eachSaysWhatTheOwnerShouldKnow() {
        val history = none + Entry(1, "2.33.0", "B", "rollback", Outcome.BAD)
        val list = VersionChoices.list(available, true, live = "2.34.0", kept = "2.33.0", history = history).associate { it.version.text to it.notes }
        assertEquals(setOf(Note.PRERELEASE), list["2.36.0-beta.1"])
        assertEquals(setOf(Note.LATEST), list["2.35.1"])
        assertEquals(setOf(Note.INSTALLED), list["2.34.0"])
        assertEquals(setOf(Note.KEPT, Note.ROLLED_BACK), list["2.33.0"])
        assertEquals(setOf(Note.PRERELEASE), list["2.3.0-canboatv2.1"])
    }

    @Test
    fun theSameVersionInBothSlotsIsTheOneInstalled() {
        val list = VersionChoices.list(available, false, live = "2.34.0", kept = "2.34.0", history = none)
        assertEquals(setOf(Note.INSTALLED), list.first { it.version.text == "2.34.0" }.notes)
    }

    @Test
    fun theLatestReleaseIsPickedFirstEvenWithABetaAbove() {
        assertEquals("2.35.1", VersionChoices.default(VersionChoices.list(available, true, null, null, none))?.version?.text)
        val betasOnly = listOf("2.36.0-beta.1", "2.36.0-beta.2").mapNotNull(ServerVersion::parse)
        assertEquals("2.36.0-beta.2", VersionChoices.default(VersionChoices.list(betasOnly, true, null, null, none))?.version?.text)
        assertNull(VersionChoices.default(emptyList()))
    }

    @Test
    fun aWebAddressIsHttpsWithAHost() {
        val url = "https://github.com/SignalK/signalk-server/releases/download/v2.36.0/signalk-server-2.36.0.tgz"
        assertEquals(url, VersionChoices.webAddress("  $url "))
        assertEquals("HTTPS://example.org/a.tgz", VersionChoices.webAddress("HTTPS://example.org/a.tgz"))
        assertNull(VersionChoices.webAddress("http://example.org/a.tgz"))
        assertNull(VersionChoices.webAddress("https:///a.tgz"))
        assertNull(VersionChoices.webAddress("example.org/a.tgz"))
        assertNull(VersionChoices.webAddress("https://example.org/a b.tgz"))
        assertNull(VersionChoices.webAddress(""))
        assertNull(VersionChoices.webAddress("file:///sdcard/a.tgz"))
    }

    @Test
    fun theLogKeepsNoCredentialFromTheAddress() {
        assertEquals(
            "https://ci.example.org/builds/signalk-server-2.36.0.tgz",
            VersionChoices.forLog("https://user:secret@ci.example.org/builds/signalk-server-2.36.0.tgz?X-Amz-Signature=abc#top"),
        )
        assertEquals("https://example.org:8443/a.tgz", VersionChoices.forLog("https://example.org:8443/a.tgz"))
        assertEquals("a web address", VersionChoices.forLog("not a url at all %"))
    }

    @Test
    fun npmsLinesLoseEveryAddressesCredentialHoweverNpmWroteIt() {
        // npm lowercases the scheme it was given, and names a redirect's target, not the address typed.
        assertEquals(
            "npm error 403 Forbidden - GET https://example.org/a.tgz",
            VersionChoices.redact("npm error 403 Forbidden - GET https://example.org/a.tgz?signature=secret"),
        )
        assertEquals(
            // The quote that closed the second address went with its query: a character, not a credential.
            "npm error fetch https://cdn.example.org/b.tgz failed, reason: 'https://cdn.example.org/b.tgz",
            VersionChoices.redact("npm error fetch https://u:p@cdn.example.org/b.tgz#x failed, reason: 'https://cdn.example.org/b.tgz?X-Amz-Signature=1'"),
        )
        assertEquals("added 758 packages in 3m", VersionChoices.redact("added 758 packages in 3m"))
        // An apostrophe is allowed in a path, and kept there.
        assertEquals(
            "npm error 404 Not Found - GET https://example.org/o'brien.tgz",
            VersionChoices.redact("npm error 404 Not Found - GET https://example.org/o'brien.tgz?signature=secret"),
        )
        // Addresses side by side, as a script prints a list: each loses its own credential, and
        // what follows a query in the same word goes with it.
        assertEquals(
            "['https://one.example/a.tgz','https://two.example/b.tgz']",
            VersionChoices.redact("['https://one.example/a.tgz','https://user:secret@two.example/b.tgz']"),
        )
        assertEquals(
            "['https://one.example/a.tgz",
            VersionChoices.redact("['https://one.example/a.tgz?s=1','https://user:secret@two.example/b.tgz']"),
        )
        // Run together, the second address is in the first's query, and goes with it.
        assertEquals(
            "https://one.example/a",
            VersionChoices.redact("https://one.example/a?sig=1https://user:secret@two.example/b?t=2"),
        )
        // Straight after a path, the second address is its own and loses its own credential.
        assertEquals(
            "https://one.example/ahttps://two.example/b",
            VersionChoices.redact("https://one.example/ahttps://user:secret@two.example/b?t=2"),
        )
        // An address inside a query, an apostrophe in its path or not, does not end it.
        assertEquals(
            "npm error GET https://ci.example/a failed",
            VersionChoices.redact("npm error GET https://ci.example/a?next=https://cdn.example/o'brien.tgz?token=secret failed"),
        )
        // An address inside a query does not end it: what follows it in the query goes too.
        assertEquals(
            "npm error GET https://ci.example/a.tgz failed",
            VersionChoices.redact("npm error GET https://ci.example/a.tgz?next=https://cdn.example/file&token=secret failed"),
        )
        // A user and password go whatever they hold: an apostrophe, a quote, an `@` of their own.
        assertEquals(
            "npm error GET https://example.org/a.tgz failed",
            VersionChoices.redact("npm error GET https://o'brien:secret@example.org/a.tgz failed"),
        )
        assertEquals(
            "npm error GET https://example.org/a.tgz failed",
            VersionChoices.redact("npm error GET https://u\"s:p@ss@example.org/a.tgz failed"),
        )
        assertEquals(
            "fetch failed: \"https://example.org:8443/a.tgz (redirected)",
            VersionChoices.redact("fetch failed: \"https://example.org:8443/a.tgz#frag\" (redirected)"),
        )
    }
}
