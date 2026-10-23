package fi.pocketsk.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Dock's CPU and memory from /proc/<pid>/stat (PLAN §8.1). */
class ProcStatTest {
    /** A `stat` line as Linux writes it: pid, (comm), then fields 3 to 52. */
    private fun line(pid: Int, comm: String, pgrp: Int, utime: Long, stime: Long, rss: Long): String {
        val f = MutableList(50) { "0" }
        f[0] = "S"
        f[5 - 3] = "$pgrp"
        f[14 - 3] = "$utime"
        f[15 - 3] = "$stime"
        f[24 - 3] = "$rss"
        return "$pid ($comm) ${f.joinToString(" ")}\n"
    }

    @Test
    fun aStatLineGivesTheGroupTheTicksAndTheResidentPages() {
        assertEquals(ProcStat.Stat(4242, 4242, 150, 9000), ProcStat.parse(line(4242, "node", 4242, 100, 50, 9000)))
    }

    @Test
    fun aCommandNameWithSpacesAndParenthesesDoesNotShiftTheFields() {
        assertEquals(ProcStat.Stat(77, 4242, 3, 12), ProcStat.parse(line(77, "sh (a) b) c", 4242, 1, 2, 12)))
    }

    @Test
    fun whatIsNotAStatLineIsNothing() {
        assertNull(ProcStat.parse(""))
        assertNull(ProcStat.parse("4242 node S 1 4242"))
        assertNull(ProcStat.parse("(node) S 1 4242"))
        assertNull(ProcStat.parse("x (node) S"))
        assertNull("too few fields", ProcStat.parse("4242 (node) S 1 4242 4242"))
        assertNull("not a number", ProcStat.parse(line(1, "node", 1, 1, 1, 1).replace(" 1 1 ", " x 1 ")))
    }

    @Test
    fun theTicksBetweenTwoLooks() {
        val before = mapOf(1 to 100L, 2 to 50L, 3 to 7L)
        val now = mapOf(1 to 130L, 2 to 50L, 4 to 5L, 3 to 2L)
        // 30 from the first, none from the second, 5 from a new one, 2 from a pid used again; the gone are nothing.
        assertEquals(37L, ProcStat.ticksBetween(before, now))
        assertEquals(0L, ProcStat.ticksBetween(before, emptyMap()))
    }

    @Test
    fun theCpuAsAPercentageOfOneCore() {
        assertEquals("a core kept busy", 100.0, ProcStat.cpuPercent(500, 5_000, 100)!!, 1e-9)
        assertEquals(4.0, ProcStat.cpuPercent(20, 5_000, 100)!!, 1e-9)
        assertEquals("two cores", 200.0, ProcStat.cpuPercent(1_000, 5_000, 100)!!, 1e-9)
        assertNull(ProcStat.cpuPercent(10, 0, 100))
        assertNull(ProcStat.cpuPercent(10, 5_000, 0))
        assertNull(ProcStat.cpuPercent(-1, 5_000, 100))
    }
}
