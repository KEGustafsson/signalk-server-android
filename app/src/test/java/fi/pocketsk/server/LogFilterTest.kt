package fi.pocketsk.server

import fi.pocketsk.server.LogFilter.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Logs card's filter. */
class LogFilterTest {

    private val lines = listOf(
        "2026-10-25 08:00:00.000  [pocketsk] starting 2.14.0 from slot A",
        "2026-10-25 08:00:01.000  signalk-server running at 0.0.0.0:3000",
        "2026-10-25 08:00:02.000  TypeError: Cannot read properties of undefined (reading 'value')",
        "2026-10-25 08:00:03.000  npm ERR! code ENOENT",
        "2026-10-25 08:00:04.000  signalk-to-nmea0183: WARN no position",
        "2026-10-25 08:00:05.000  [pocketsk] the server failed its health check",
        "2026-10-25 08:00:06.000  forewarned is forearmed",
    )

    @Test
    fun withNothingChosenTheNewestLinesShow() {
        assertEquals(lines.takeLast(3), LogFilter.select(lines, Kind.ALL, "  ", 3))
    }

    @Test
    fun problemsAreErrorsWarningsAndFailures() {
        assertEquals(
            listOf(lines[2], lines[3], lines[4], lines[5]),
            LogFilter.select(lines, Kind.PROBLEMS, "", 10),
        )
        assertFalse(LogFilter.matches(lines[6], Kind.PROBLEMS, emptyList()))
    }

    @Test
    fun theAppsOwnLinesAreTheMarkedOnes() {
        assertEquals(listOf(lines[0], lines[5]), LogFilter.select(lines, Kind.APP, "", 10))
    }

    @Test
    fun everyWordMustBeThereInAnyCaseAndOrder() {
        assertEquals(listOf(lines[5]), LogFilter.select(lines, Kind.ALL, "HEALTH  server", 10))
        assertEquals(listOf(lines[0]), LogFilter.select(lines, Kind.APP, "slot", 10))
        assertEquals(emptyList<String>(), LogFilter.select(lines, Kind.PROBLEMS, "slot", 10))
        assertTrue(LogFilter.words("  A  b ").let { it == listOf("a", "b") })
    }

    @Test
    fun theNewestMatchesAreKeptOldestFirst() {
        assertEquals(listOf(lines[4], lines[5]), LogFilter.select(lines, Kind.PROBLEMS, "", 2))
    }
}
