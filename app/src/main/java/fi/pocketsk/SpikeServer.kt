package fi.pocketsk

import android.content.Context
import fi.pocketsk.node.NodeProcess
import fi.pocketsk.node.NodeRuntime
import fi.pocketsk.signalk.SignalKClient
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Spike R4 (docs/PLAN.md §3): start the signalk-server that R3 installed into slot A, with
 * `--sample-nmea0183-data` so there is something to see, and say when it answers.
 *
 * It runs exactly as PLAN §6's supervisor will start it, `node <slot>/lib/node_modules/
 * signalk-server/bin/signalk-server` with NodeRuntime's environment, but without the
 * supervisor: no foreground service, no restart, no wake lock. Readiness is the same test the
 * supervisor and the installer's smoke test use, `GET /signalk` answering with its endpoints.
 *
 * The server is one process per app process, so the running one is held here and not in the
 * activity: turning the phone round must not start a second server on the same port, and the
 * screen that takes over has to hear when it ends. Its output is kept here for the same reason,
 * the last [SpikeInstall.TAIL_LINES] lines, for whichever screen [observe]s it.
 */
object SpikeServer {

    /** Where the spike server answers; Signal K's default port, on the loopback the browser opens. */
    const val BASE = "http://127.0.0.1:3000"

    /** The screen's view of the server. Both calls come from worker threads. */
    interface Observer {
        /** The last lines of output so far, and whether the server still runs. */
        fun onProgress(text: String, running: Boolean)

        /**
         * `/signalk` answered, [ms] after the start of run [generation]; once per start, to the
         * observer attached then or, if none is, to the next one. Check [isCurrent] with
         * [generation] before acting on it later: the server may have been stopped since.
         */
        fun onReady(ms: Long, generation: Long)
    }

    @Volatile private var process: NodeProcess? = null
    @Volatile private var observer: Observer? = null
    @Volatile private var latest = ""

    /** Counts runs, start and stop alike, so a readiness from an older run is recognised. */
    @Volatile private var generation = 0L

    /** A readiness no observer was attached to receive (a rotation in between); guarded by this. */
    private var pendingReady: Pair<Long, Long>? = null

    /** The last lines of output, oldest first; guarded by itself, as two threads add to it. */
    private val tail = ArrayDeque<String>()
    private var lastPost = 0L

    val isRunning: Boolean get() = process?.process?.isAlive == true

    /** True while run [generation] is the one running, neither stopped nor replaced. */
    fun isCurrent(generation: Long): Boolean = generation == this.generation && isRunning

    /**
     * Makes [observer] the one told, and tells it the output so far at once, on the caller's
     * thread, and a readiness that arrived while no observer was attached.
     */
    @Synchronized
    fun observe(observer: Observer) {
        this.observer = observer
        observer.onProgress(latest, isRunning)
        pendingReady?.let { (ms, run) -> if (isCurrent(run)) observer.onReady(ms, run) }
        pendingReady = null
    }

    /** Stops telling [observer], unless another has taken its place already. */
    @Synchronized
    fun detach(observer: Observer) {
        if (this.observer === observer) this.observer = null
    }

    /**
     * Starts the server unless it runs already, its output to the [Observer] and to [logFile].
     * Throws when there is nothing to start or the exec fails.
     */
    @Synchronized
    @Throws(IOException::class)
    fun start(context: Context, runtime: NodeRuntime, logFile: File) {
        if (isRunning) return
        val main = File(runtime.slots, "A/lib/node_modules/signalk-server/bin/signalk-server")
        if (!main.isFile) throw IOException("$main is not there: run the R3 install first")
        runtime.ensureLayout()
        logFile.parentFile?.mkdirs()
        synchronized(tail) { tail.clear() }
        latest = ""
        pendingReady = null
        val run = ++generation
        val started = System.nanoTime()
        val node = runtime.spawn(main.absolutePath, "--sample-nmea0183-data")
        val p = node.process
        process = node
        thread(name = "spike-server-log") {
            try {
                logFile.bufferedWriter().use { log ->
                    p.inputStream.bufferedReader().forEachLine { line ->
                        log.appendLine(line)
                        log.flush()
                        add(line, now = false)
                    }
                }
            } catch (e: Exception) {
                // Broadly, as on any plain thread. A server nobody reads fills its pipe and
                // stalls, so it goes when its reader does.
                add("log failed: $e", now = true)
                node.kill()
            }
            val code = p.waitFor()
            // A plugin's helper outlives the server otherwise, and the next start finds it still
            // holding a port or a serial device.
            val whole = node.reap()
            if (process === node) process = null
            add(if (whole) "exit $code" else "exit $code (no process group: anything it started may still run)", now = true)
        }
        thread(name = "spike-server-ready") {
            val client = SignalKClient(context, BASE, null)
            while (p.isAlive && System.nanoTime() - started < READY_TIMEOUT_NS) {
                if (client.endpoints() is SignalKClient.Result.Ok) {
                    val ms = (System.nanoTime() - started) / 1_000_000
                    add("(answering on $BASE after $ms ms; opening the admin UI)", now = true)
                    ready(ms, run)
                    return@thread
                }
                Thread.sleep(POLL_MS)
            }
            if (p.isAlive) add("(no answer on $BASE after ${READY_TIMEOUT_NS / 1_000_000_000} s)", now = true)
        }
    }

    /**
     * Stops the server, if one runs: SIGTERM to its process group, the server and whatever its
     * plugins started, which Signal K shuts down on cleanly; what is still there when the server
     * has gone is killed then. A readiness still on its way belongs to the run stopped here, so
     * it opens nothing.
     */
    @Synchronized
    fun stop() {
        generation++
        pendingReady = null
        process?.terminate()
    }

    /**
     * Hands run [run]'s readiness to the observer, or keeps it for the next one, under the same
     * lock as [observe] so it is neither lost nor delivered twice while the screen is replaced.
     */
    @Synchronized
    private fun ready(ms: Long, run: Long) {
        if (!isCurrent(run)) return
        val o = observer
        if (o != null) o.onReady(ms, run) else pendingReady = ms to run
    }

    /** Adds [line] and tells the observer, at most every half second unless [now]. */
    private fun add(line: String, now: Boolean) {
        val text = synchronized(tail) {
            tail.addLast(line)
            while (tail.size > SpikeInstall.TAIL_LINES) tail.removeFirst()
            val t = System.nanoTime()
            if (!now && t - lastPost < UPDATE_INTERVAL_NS) return
            lastPost = t
            tail.joinToString("\n")
        }
        latest = text
        observer?.onProgress(text, isRunning)
    }

    private const val POLL_MS = 1_000L
    private const val UPDATE_INTERVAL_NS = 500_000_000L
    /** PLAN §5.2 gives the smoke test 60 s and promotion 90 s; a first start on a phone gets the longer. */
    private const val READY_TIMEOUT_NS = 90_000_000_000L
}
