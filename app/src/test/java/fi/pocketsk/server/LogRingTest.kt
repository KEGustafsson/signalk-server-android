package fi.pocketsk.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-memory log keeps the newest output within its size. */
class LogRingTest {
    @Test
    fun keepsTheNewestLinesWithinTheLimit() {
        val ring = LogRing(maxChars = 20)
        for (i in 1..10) ring.add("line$i")
        // Each "lineN" costs six with its newline, "line10" seven: three fit in twenty.
        assertEquals(listOf("line8", "line9", "line10"), ring.tail(100))
        assertTrue(ring.size <= 20)
    }

    @Test
    fun tailCountsFromTheEnd() {
        val ring = LogRing()
        for (i in 1..5) ring.add("$i")
        assertEquals(listOf("4", "5"), ring.tail(2))
    }

    @Test
    fun aLineLongerThanTheRingIsCut() {
        val ring = LogRing(maxChars = 10)
        ring.add("a")
        ring.add("x".repeat(50))
        assertEquals(listOf("x".repeat(9)), ring.tail(10))
        assertEquals(10, ring.size)
    }

    @Test
    fun clearEmptiesIt() {
        val ring = LogRing()
        ring.add("a")
        ring.clear()
        assertEquals(emptyList<String>(), ring.tail(10))
        assertEquals(0, ring.size)
    }
}
