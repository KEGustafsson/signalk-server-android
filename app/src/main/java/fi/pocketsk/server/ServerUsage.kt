package fi.pocketsk.server

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

/**
 * The running server's uptime, CPU and memory for the Dock's Status card, looked at every
 * [SAMPLE_MS] while the Dock is in front ([start], [stop]) and never otherwise: reading `/proc`
 * costs little, but nothing needs it with no screen to show it on.
 *
 * Each look lists `/proc` and reads the `stat` of every process in the server's group
 * ([ServerState.Running.group]). Android hides other apps' processes from `/proc`, so the list is
 * this app's own and short. The arithmetic is [ProcStat]'s. Reads happen on a thread of their own
 * ("dock-usage"), each [start] with a [Looks] of its own, so a look still under way when [stop]
 * comes neither reaches the screen nor leaks its measure into the next start; [onSample] is
 * called on the main thread.
 */
internal class ServerUsage(private val onSample: (Usage?) -> Unit) {

    /**
     * One look: the server [up] for so long (ms), its CPU as a percentage of one core since the
     * look before (null on the first look of a run, or without a group to read), its resident
     * memory in bytes and the processes it counts (null and 0 without a group).
     */
    data class Usage(val up: Long, val cpuPercent: Double?, val memoryBytes: Long?, val processes: Int)

    private val main = Handler(Looper.getMainLooper())
    private var looks: Looks? = null

    /** Main thread: starts looking, at once and then every [SAMPLE_MS]. */
    fun start() {
        if (looks == null) looks = Looks().also { it.begin() }
    }

    /** Main thread: stops looking; the next [start] begins a fresh measure. */
    fun stop() {
        looks?.end()
        looks = null
    }

    /** The looks of one [start]: its thread, and the measure the CPU is taken against. */
    private inner class Looks : Runnable {
        private val thread = HandlerThread("dock-usage").apply { start() }
        private val handler = Handler(thread.looper)
        @Volatile private var active = true

        // On [thread] only.
        private var run: Long? = null
        private var lastTicks: Map<Int, Long>? = null
        private var lastAt = 0L

        fun begin() {
            handler.post(this)
        }

        fun end() {
            active = false
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
        }

        override fun run() {
            // Broadly: a vendor's /proc that reads oddly costs the figures, never the app.
            val usage = try {
                sample()
            } catch (_: Exception) {
                null
            }
            main.post { if (active) onSample(usage) }
            if (active) handler.postDelayed(this, SAMPLE_MS)
        }

        private fun sample(): Usage? {
            val state = ServerStatus.state as? ServerState.Running
            if (state == null) {
                run = null
                lastTicks = null
                return null
            }
            val now = SystemClock.elapsedRealtime()
            val up = now - state.startedAt
            // A new run is measured from scratch: its pids say nothing about the last run's.
            if (run != state.startedAt) {
                run = state.startedAt
                lastTicks = null
            }
            val group = state.group ?: return Usage(up, null, null, 0)
            val stats = File("/proc").listFiles { f -> f.name.all(Char::isDigit) }.orEmpty().mapNotNull { dir ->
                try {
                    ProcStat.parse(File(dir, "stat").readText())
                } catch (_: IOException) {
                    // Gone between the listing and the read.
                    null
                }
            }.filter { it.group == group }
            val ticks = stats.associate { it.pid to it.ticks }
            val cpu = lastTicks?.let { ProcStat.cpuPercent(ProcStat.ticksBetween(it, ticks), now - lastAt, TICKS_PER_SECOND) }
            lastTicks = ticks
            lastAt = now
            val memory = if (stats.isEmpty()) null else stats.sumOf { it.rssPages } * PAGE_SIZE
            return Usage(up, cpu, memory, stats.size)
        }
    }

    companion object {
        const val SAMPLE_MS = 5_000L
        private val TICKS_PER_SECOND = Os.sysconf(OsConstants._SC_CLK_TCK)
        private val PAGE_SIZE = Os.sysconf(OsConstants._SC_PAGESIZE)
    }
}
