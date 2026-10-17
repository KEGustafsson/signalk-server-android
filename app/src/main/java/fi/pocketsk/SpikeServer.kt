package fi.pocketsk

import android.content.Context
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

    /** Where the spike server answers; Signal K's default port, on the loopback the WebView uses. */
    const val BASE = "http://127.0.0.1:3000"

    /** The screen's view of the server. Both calls come from worker threads. */
    interface Observer {
        /** The last lines of output so far, and whether the server still runs. */
        fun onProgress(text: String, running: Boolean)

        /** `/signalk` answered, [ms] after the start; once per start. */
        fun onReady(ms: Long)
    }

    @Volatile private var process: Process? = null
    @Volatile private var observer: Observer? = null
    @Volatile private var latest = ""

    /** The last lines of output, oldest first; guarded by itself, as two threads add to it. */
    private val tail = ArrayDeque<String>()
    private var lastPost = 0L

    val isRunning: Boolean get() = process?.isAlive == true

    /** Makes [observer] the one told, and tells it the output so far at once, on the caller's thread. */
    @Synchronized
    fun observe(observer: Observer) {
        this.observer = observer
        observer.onProgress(latest, isRunning)
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
        val started = System.nanoTime()
        val p = runtime.spawn(main.absolutePath, "--sample-nmea0183-data")
        process = p
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
                p.destroyForcibly()
            }
            val code = p.waitFor()
            if (process === p) process = null
            add("exit $code", now = true)
        }
        thread(name = "spike-server-ready") {
            val client = SignalKClient(context, BASE, null)
            while (p.isAlive && System.nanoTime() - started < READY_TIMEOUT_NS) {
                if (client.endpoints() is SignalKClient.Result.Ok) {
                    val ms = (System.nanoTime() - started) / 1_000_000
                    add("(answering on $BASE after $ms ms; opening the admin UI)", now = true)
                    observer?.onReady(ms)
                    return@thread
                }
                Thread.sleep(POLL_MS)
            }
        }
    }

    /** Stops the server, if one runs: SIGTERM, which Signal K shuts down on cleanly. */
    fun stop() {
        process?.destroy()
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
