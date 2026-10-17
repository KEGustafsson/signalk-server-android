package fi.pocketsk

import fi.pocketsk.node.NodeRuntime
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Spike R3 (docs/PLAN.md §3): `npm install -g signalk-server` on the phone, into slot A, with the
 * phone's own shell for lifecycle scripts. Global, with the slot as its prefix, because that is
 * how a Pi installs it and the layout signalk-server expects: a local install hoists its
 * dependencies to the slot's node_modules, and the server then cannot find
 * `@signalk/server-admin-ui`, which it looks for under its own node_modules (the admin UI
 * answers 500). Installed that way it lives in `<slot>/lib/node_modules/signalk-server`. Node runs `shell: true` scripts with
 * `/system/bin/sh` on Android, so npm needs no script-shell setting; what nobody knows yet is
 * which of signalk-server's dependencies run install scripts toybox cannot handle, or exec a
 * file from app storage by name (`node_modules/.bin`, node-gyp), which Android refuses for an
 * app targeting API 29+ (§4.2, §15).
 *
 * The run is blocking and long (hundreds of packages over the phone's network), so it lives on
 * its own thread and reports to its [Observer] at most every half second with the last
 * [TAIL_LINES] lines; the whole log goes to `files/logs/spike-r3.log` for the diagnostics that
 * follow. After a clean install a small node script lists what R3 is about: the installed
 * version, the package count, and the packages with install scripts or a binding.gyp.
 *
 * One install runs per process, whatever happens to the screen that started it ([start]): a
 * rotation recreates the activity while npm keeps going, and a second install would delete the
 * slot and the log under the first. The new activity [observe]s the one running.
 */
class SpikeInstall private constructor(private val runtime: NodeRuntime, private val logFile: File) {

    /** The screen's view of the run: the tail so far, and whether the run has ended. */
    fun interface Observer {
        fun onProgress(text: String, finished: Boolean)
    }

    /** The last lines of the run, oldest first. Thread-confined to the install thread. */
    private val tail = ArrayDeque<String>()

    @Volatile private var observer: Observer? = null
    @Volatile private var latest = ""
    @Volatile private var finished = false

    /** True until the run has ended, its final report included. */
    val isRunning: Boolean get() = !finished

    /**
     * True once npm had to be killed (out of time, or its log could not be written). Killing npm
     * does not kill the lifecycle scripts it started, and Android gives an app no handle on them:
     * no child pids, and once npm is gone they belong to init. They end with the app's process
     * group, which Android kills on a force stop, so until then [start] refuses another install
     * into the slot they may still be writing to. The real installer kills the whole group (PLAN §5).
     */
    @Volatile var mayHaveOrphans = false
        private set

    /**
     * Makes [observer] the one told about progress, and tells it the state so far at once, on
     * the caller's thread; later calls come from the install thread. Both sides write before
     * they read volatile fields, so the final report reaches an observer attached at any moment,
     * at worst twice.
     */
    @Synchronized
    fun observe(observer: Observer) {
        this.observer = observer
        observer.onProgress(latest, finished)
    }

    /** Stops telling [observer], unless another has taken its place already. */
    @Synchronized
    fun detach(observer: Observer) {
        if (this.observer === observer) this.observer = null
    }

    private fun begin() {
        thread(name = "spike-npm-install") {
            val started = System.nanoTime()
            try {
                run()
            } catch (e: Exception) {
                // Broadly: an exception that escapes a plain thread kills the app, and the
                // report is the point of the spike.
                add("failed: $e")
            }
            if (mayHaveOrphans) add(ORPHANS)
            add("(${(System.nanoTime() - started) / 1_000_000_000} s in all; full log: $logFile)")
            publish(done = true)
        }
    }

    private fun run() {
        runtime.ensureExtracted()
        val slot = File(runtime.slots, "A")
        slot.deleteRecursively()
        if (!slot.mkdirs()) throw IOException("could not create $slot")
        logFile.parentFile?.mkdirs()
        add("$ npm install -g --prefix $slot ${INSTALL_ARGS.joinToString(" ")}")
        var lastUpdate = 0L
        val code = logFile.bufferedWriter().use { log ->
            val npm = runtime.npm("install", "-g", "--prefix", slot.absolutePath, *INSTALL_ARGS)
            // From here until drain() says npm ended by itself, it may be killed with scripts still
            // running. Not before: a log that cannot be opened or an npm that never started leaves
            // nothing behind.
            mayHaveOrphans = true
            drain(npm, INSTALL_TIMEOUT_S) { line ->
                log.appendLine(line)
                add(line)
                val now = System.nanoTime()
                if (now - lastUpdate > UPDATE_INTERVAL_NS) {
                    lastUpdate = now
                    publish(done = false)
                }
            }.also {
                // Cleared here, inside the block: closing the log flushes it and can throw, and
                // npm has ended by then whatever the log does.
                if (it != null) mayHaveOrphans = false
            }
        }
        add(if (code == null) "timed out after ${INSTALL_TIMEOUT_S / 60} min" else "exit $code")
        if (code != 0) return
        add("")
        add("$ node -e <summary> $slot")
        val summary = drain(runtime.spawn("-e", SUMMARY_SCRIPT, slot.absolutePath), SUMMARY_TIMEOUT_S, ::add)
        if (summary != 0) add(if (summary == null) "timed out after $SUMMARY_TIMEOUT_S s" else "exit $summary")
    }

    /**
     * Reads [process]'s output to its end, line by line, and returns its exit code, or null when
     * the reading had not ended after [timeoutS] seconds and the process was killed. The deadline
     * runs on its own thread, because a reader blocks for as long as the pipe is open, and it
     * counts the reading rather than the process: npm can exit while a lifecycle script it
     * started still holds the pipe, so the deadline closes the stream too. Whichever of the two
     * finishes first decides the outcome, so a reading that ends as the deadline passes is not
     * also called a timeout. The process is killed and reaped on every way out, a failed log
     * write included, so nothing outlives the run and a retry never shares the slot with the
     * last one.
     */
    private fun drain(process: Process, timeoutS: Long, onLine: (String) -> Unit): Int? {
        val settled = AtomicBoolean(false)
        val drained = CountDownLatch(1)
        thread(name = "spike-deadline", isDaemon = true) {
            if (!drained.await(timeoutS, TimeUnit.SECONDS) && settled.compareAndSet(false, true)) {
                process.destroyForcibly()
                try {
                    process.inputStream.close()
                } catch (_: IOException) {
                    // Closed already; the reader ends either way.
                }
            }
        }
        var read = false
        var timedOut = false
        try {
            process.inputStream.bufferedReader().forEachLine(onLine)
            read = true
        } catch (e: IOException) {
            // The deadline closing the stream under the reader is a timeout, not a failure.
            if (settled.get()) timedOut = true else throw e
        } finally {
            if (!settled.compareAndSet(false, true)) timedOut = true
            drained.countDown()
            // Only when the reading failed or ran out of time: after a clean end of the stream
            // the process may not have exited yet, and killing it then would lose its code.
            if (!read || timedOut) {
                process.destroyForcibly()
                process.waitFor()
            }
        }
        val code = process.waitFor()
        return if (timedOut) null else code
    }

    private fun add(line: String) {
        tail.addLast(line)
        while (tail.size > TAIL_LINES) tail.removeFirst()
    }

    private fun publish(done: Boolean) {
        latest = tail.joinToString("\n")
        finished = done
        observer?.onProgress(latest, done)
    }

    companion object {
        /** The install this process started last, running or ended. Only [start] writes it. */
        @Volatile var current: SpikeInstall? = null
            private set

        /**
         * Starts an install into slot A, unless one is already running in this process or the
         * last one may have left scripts running ([mayHaveOrphans]); either way returns the one
         * that holds the slot, for the caller to [observe].
         */
        @Synchronized
        fun start(runtime: NodeRuntime, logFile: File): SpikeInstall {
            current?.takeIf { it.isRunning || it.mayHaveOrphans }?.let { return it }
            return SpikeInstall(runtime, logFile).also {
                current = it
                it.begin()
            }
        }

        /**
         * What R3 installs, and how. `--foreground-scripts` puts every lifecycle script's own
         * output in the log, which is the evidence R3 wants; audit and fund are network calls
         * that teach nothing here.
         */
        val INSTALL_ARGS = arrayOf("signalk-server", "--omit=dev", "--no-audit", "--no-fund", "--foreground-scripts")

        /** The lines kept on screen. The log file has all of them. */
        const val TAIL_LINES = 300

        private const val UPDATE_INTERVAL_NS = 500_000_000L
        private const val INSTALL_TIMEOUT_S = 20 * 60L
        private const val SUMMARY_TIMEOUT_S = 60L
        private const val ORPHANS = "npm was killed: scripts it started may still be running in slot A. " +
            "Force-stop the app before installing again."

        /**
         * Walks the installed tree (argv[1] is the slot, the tree is its lib/node_modules, as a
         * global install lays it out) and prints three lines: the
         * signalk-server version and package count, the packages with install-time scripts,
         * and the packages with a binding.gyp, which npm builds with node-gyp when they ship no
         * script of their own.
         */
        val SUMMARY_SCRIPT = """
            const fs = require('fs'), path = require('path');
            const root = path.join(process.argv[1], 'lib', 'node_modules');
            const pkgs = [];
            (function walk(dir) {
              for (const n of fs.existsSync(dir) ? fs.readdirSync(dir) : []) {
                if (n.startsWith('.')) continue;
                const p = path.join(dir, n);
                if (n.startsWith('@')) { walk(p); continue; }
                const j = path.join(p, 'package.json');
                if (fs.existsSync(j)) pkgs.push([p, JSON.parse(fs.readFileSync(j, 'utf8'))]);
                walk(path.join(p, 'node_modules'));
              }
            })(root);
            const sk = pkgs.find(([p]) => p === path.join(root, 'signalk-server'));
            console.log('signalk-server ' + (sk ? sk[1].version : 'NOT INSTALLED') + ', ' + pkgs.length + ' packages');
            const hook = (s) => s && (s.preinstall || s.install || s.postinstall);
            const scripts = pkgs.filter(([, j]) => hook(j.scripts)).map(([, j]) => j.name + ': ' + hook(j.scripts));
            console.log('install scripts (' + scripts.length + '): ' + scripts.join(' | '));
            const gyp = pkgs.filter(([p]) => fs.existsSync(path.join(p, 'binding.gyp'))).map(([, j]) => j.name);
            console.log('binding.gyp (' + gyp.length + '): ' + gyp.join(', '));
        """.trimIndent()
    }
}
