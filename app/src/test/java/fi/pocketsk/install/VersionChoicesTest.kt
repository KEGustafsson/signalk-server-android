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
}
