package fi.pocketsk.install

import fi.pocketsk.install.InstallHistory.Entry
import fi.pocketsk.install.InstallHistory.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the history remembers: bad versions, the order it lists them in, and its size. */
class InstallHistoryTest {
    private fun entry(at: Long, version: String, outcome: Outcome) = Entry(at, version, "A", "npm", outcome)

    @Test
    fun onlyTheHealthGateMarksAVersionBad() {
        val history = InstallHistory()
            .plus(entry(1, "2.33.0", Outcome.PROMOTED))
            .plus(entry(2, "2.33.0", Outcome.ROLLED_BACK))
            .plus(entry(3, "2.32.0", Outcome.FAILED))
            .plus(entry(4, "2.34.0", Outcome.BAD))
        // Rolled back by hand, or failed before promotion: nothing says the version is broken.
        assertFalse(history.isBad("2.33.0"))
        assertFalse(history.isBad("2.32.0"))
        assertTrue(history.isBad("2.34.0"))
    }

    @Test
    fun listsNewestFirst() {
        val history = InstallHistory(listOf(entry(5, "b", Outcome.PROMOTED), entry(9, "c", Outcome.PROMOTED), entry(1, "a", Outcome.PROMOTED)))
        assertEquals(listOf("c", "b", "a"), history.newestFirst.map { it.version })
    }

    @Test
    fun keepsTheNewestEntriesOnly() {
        var history = InstallHistory()
        for (i in 1..InstallHistory.MAX_ENTRIES + 5) history += entry(i.toLong(), "v$i", Outcome.PROMOTED)
        assertEquals(InstallHistory.MAX_ENTRIES, history.entries.size)
        assertEquals("v6", history.entries.first().version)
        assertEquals("v${InstallHistory.MAX_ENTRIES + 5}", history.entries.last().version)
    }
}
