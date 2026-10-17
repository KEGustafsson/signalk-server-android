package fi.pocketsk

import fi.pocketsk.node.NodeRuntime
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Spike R3 (docs/PLAN.md §3): `npm install signalk-server` on the phone, into slot A, with the
 * phone's own shell for lifecycle scripts. Node runs `shell: true` scripts with
 * `/system/bin/sh` on Android, so npm needs no script-shell setting; what nobody knows yet is
 * which of signalk-server's dependencies run install scripts toybox cannot handle, or exec a
 * file from app storage by name (`node_modules/.bin`, node-gyp), which Android refuses for an
 * app targeting API 29+ (§4.2, §15).
 *
 * The run is blocking and long (hundreds of packages over the phone's network), so it lives on
 * its own thread and reports through [onUpdate] at most every half second with the last
 * [TAIL_LINES] lines; the whole log goes to `files/logs/spike-r3.log` for the diagnostics that
 * follow. After a clean install a small node script lists what R3 is about: the installed
 * version, the package count, and the packages with install scripts or a binding.gyp.
 */
class SpikeInstall(private val runtime: NodeRuntime, private val logFile: File) {

    /** The last lines of the run, oldest first. Thread-confined to the install thread. */
    private val tail = ArrayDeque<String>()

    /**
     * Starts the install on its own thread. [onUpdate] gets the tail so far, from that thread;
     * [onDone] gets the final report, also from that thread, exactly once.
     */
    fun start(onUpdate: (String) -> Unit, onDone: (String) -> Unit) {
        thread(name = "spike-npm-install") {
            val started = System.nanoTime()
            try {
                run(onUpdate)
            } catch (e: IOException) {
                add("failed: $e")
            }
            add("(${(System.nanoTime() - started) / 1_000_000_000} s in all; full log: $logFile)")
            onDone(text())
        }
    }

    private fun run(onUpdate: (String) -> Unit) {
        runtime.ensureExtracted()
        val slot = File(runtime.slots, "A")
        slot.deleteRecursively()
        if (!slot.mkdirs()) throw IOException("could not create $slot")
        logFile.parentFile?.mkdirs()
        add("$ npm install --prefix $slot ${INSTALL_ARGS.joinToString(" ")}")
        val process = runtime.npm("install", "--prefix", slot.absolutePath, *INSTALL_ARGS)
        // npm can stall on a dead network for longer than anyone waits; the reader below only
        // ends when the process does, so a watchdog ends the process.
        thread(name = "spike-npm-watchdog", isDaemon = true) {
            if (!process.waitFor(INSTALL_TIMEOUT_MIN, TimeUnit.MINUTES)) process.destroyForcibly()
        }
        var lastUpdate = 0L
        logFile.bufferedWriter().use { log ->
            process.inputStream.bufferedReader().forEachLine { line ->
                log.appendLine(line)
                add(line)
                val now = System.nanoTime()
                if (now - lastUpdate > UPDATE_INTERVAL_NS) {
                    lastUpdate = now
                    onUpdate(text())
                }
            }
        }
        val code = process.waitFor()
        add("exit $code")
        if (code != 0) return
        add("")
        add("$ node -e <summary> $slot")
        val summary = runtime.spawn("-e", SUMMARY_SCRIPT, slot.absolutePath)
        summary.inputStream.bufferedReader().forEachLine { add(it) }
        if (!summary.waitFor(SUMMARY_TIMEOUT_S, TimeUnit.SECONDS)) summary.destroyForcibly()
    }

    private fun add(line: String) {
        tail.addLast(line)
        while (tail.size > TAIL_LINES) tail.removeFirst()
    }

    private fun text(): String = tail.joinToString("\n")

    companion object {
        /**
         * What R3 installs, and how. `--foreground-scripts` puts every lifecycle script's own
         * output in the log, which is the evidence R3 wants; audit and fund are network calls
         * that teach nothing here.
         */
        val INSTALL_ARGS = arrayOf("signalk-server", "--omit=dev", "--no-audit", "--no-fund", "--foreground-scripts")

        /** The lines kept on screen. The log file has all of them. */
        const val TAIL_LINES = 300

        private const val UPDATE_INTERVAL_NS = 500_000_000L
        private const val INSTALL_TIMEOUT_MIN = 20L
        private const val SUMMARY_TIMEOUT_S = 60L

        /**
         * Walks the installed tree (argv[1] is the slot) and prints three lines: the
         * signalk-server version and package count, the packages with install-time scripts,
         * and the packages with a binding.gyp, which npm builds with node-gyp when they ship no
         * script of their own.
         */
        val SUMMARY_SCRIPT = """
            const fs = require('fs'), path = require('path');
            const root = path.join(process.argv[1], 'node_modules');
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
