package fi.pocketsk.server

/**
 * The server's CPU time and memory from Linux's `/proc/<pid>/stat` (proc(5)), for the Dock's
 * Status card. Pure, so it is unit-tested; [ServerUsage] reads the files.
 *
 * The server is a process group ([fi.pocketsk.node.NodeProcess]): signalk-server and whatever a
 * plugin starts. So the numbers are the group's, summed over its processes. Memory is each
 * process's resident set added up, which counts a page shared between two of them twice: an upper
 * bound, the figure a phone's own process list gives.
 */
internal object ProcStat {

    /** One process as its `stat` line gives it: its [group], CPU [ticks] (user and system) and resident pages. */
    data class Stat(val pid: Int, val group: Int, val ticks: Long, val rssPages: Long)

    /**
     * One `stat` line. The command name (field 2) is in parentheses and may itself hold spaces
     * and parentheses, so the fields are counted from the last `)`. Null for a line that is not one.
     */
    fun parse(line: String): Stat? {
        val open = line.indexOf('(')
        val close = line.lastIndexOf(')')
        if (open <= 0 || close < open) return null
        val pid = line.substring(0, open).trim().toIntOrNull() ?: return null
        // From field 3 (state) on; field n is at n - 3.
        val f = line.substring(close + 1).trim().split(' ')
        if (f.size <= RSS - FIRST) return null
        val group = f[PGRP - FIRST].toIntOrNull() ?: return null
        val utime = f[UTIME - FIRST].toLongOrNull() ?: return null
        val stime = f[STIME - FIRST].toLongOrNull() ?: return null
        val rss = f[RSS - FIRST].toLongOrNull() ?: return null
        if (utime < 0 || stime < 0 || rss < 0) return null
        return Stat(pid, group, utime + stime, rss)
    }

    /**
     * The CPU ticks the group spent between two looks, each a map of pid to its ticks. A process
     * that is new counts all its ticks, since it started in between; one that has gone counts
     * nothing more, and a pid whose ticks went down is a different process with the same pid,
     * counted like a new one.
     */
    fun ticksBetween(before: Map<Int, Long>, now: Map<Int, Long>): Long = now.entries.sumOf { (pid, t) ->
        val was = before[pid]
        if (was == null || t < was) t else t - was
    }

    /**
     * [ticks] over [elapsedMs] as a percentage of one core, as `top` gives it: a server that keeps
     * one core busy reads 100. Null when there is no span to measure over.
     */
    fun cpuPercent(ticks: Long, elapsedMs: Long, ticksPerSecond: Long): Double? =
        if (elapsedMs <= 0 || ticksPerSecond <= 0 || ticks < 0) null else ticks * 100_000.0 / (elapsedMs * ticksPerSecond)

    private const val FIRST = 3
    private const val PGRP = 5
    private const val UTIME = 14
    private const val STIME = 15
    private const val RSS = 24
}
