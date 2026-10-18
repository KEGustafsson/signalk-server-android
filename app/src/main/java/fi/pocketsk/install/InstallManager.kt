package fi.pocketsk.install

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import fi.pocketsk.node.NodeRuntime
import fi.pocketsk.signalk.SignalKClient
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.Writer
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Installs signalk-server into the inactive slot, proves it starts, and makes it live
 * (docs/PLAN.md §5.1–5.2):
 *
 * 1. pick the slot `files/sk/current` does not point at ([Slots.inactive]) and wipe it;
 * 2. `npm install -g --prefix <slot> <spec>`, its output to [Listener] and `files/logs/install.log`;
 * 3. smoke test: the new server started on port 3999 with a copy of the live configuration and
 *    sample data, until `GET /signalk` answers or 60 s pass, then stopped with its process group;
 * 4. promote: `current` repointed in one rename, so a reader sees the old link or the new one;
 * 5. record the outcome in `files/sk/install-history.json` ([InstallHistory]).
 *
 * The inactive slot holds the version before the live one, the one [rollback] goes back to, so
 * an install gives that up: with two slots there is room for the live version and one other.
 * A failed install empties the slot again rather than leave a server there that never passed
 * its smoke test, which [rollback] would otherwise make live.
 *
 * Restarting the live server from the new slot and watching it ([HealthGate]) is the
 * supervisor's (§6, SignalKService); a failed gate comes back here as [rollback]. Plugins and
 * webapps live in `.signalk/node_modules`, outside the slots, so they survive both.
 *
 * Everything here blocks (npm runs for minutes) and is called from a worker thread, one install
 * or rollback at a time in the process: [InstallJob] runs it for the screens.
 *
 * Unlike the plan's first sketch this is a blocking API with a listener, not a suspend function
 * returning a Flow: the app has no coroutines and adds no library for one caller.
 */
internal class InstallManager(context: Context, private val runtime: NodeRuntime) {
    private val app = context.applicationContext
    private val sk = runtime.slots.parentFile ?: File(app.filesDir, "sk")
    private val historyFile = File(sk, "install-history.json")
    private val versionsFile = File(sk, "versions.json")
    private val logFile = File(app.filesDir, "logs/install.log")

    /** Where an install comes from (§5.1). The GitHub mirror is not here yet. */
    sealed interface Source {
        /** The npm registry: [version], or the latest release when null. */
        data class Registry(val version: String? = null) : Source

        /** A `.tgz` made by `npm pack` on any machine, for a boat with no internet (sideload). */
        data class Tarball(val file: File) : Source
    }

    /** How an install is going; [tail] is the last lines of its log. */
    sealed interface State {
        data class Installing(val slot: String, val tail: String) : State
        data class Verifying(val slot: String, val tail: String) : State
        /** [history] includes this install, so a screen can show it without reading the file itself. */
        data class Done(val version: String, val slot: String, val history: InstallHistory) : State
        data class Failed(val reason: String, val tail: String) : State
    }

    fun interface Listener {
        /** Called on the installing thread, at most twice a second while output streams. */
        fun onState(state: State)
    }

    /** The slot `current` points at, or null on a phone where nothing has been made live. */
    fun liveSlot(): String? = try {
        Slots.nameOf(Os.readlink(runtime.current.absolutePath))
    } catch (_: ErrnoException) {
        null
    }

    /** The signalk-server version installed in [slot], read from its package.json; null if none. */
    fun installedVersion(slot: String): String? {
        val pkg = File(slotDir(slot), "lib/node_modules/signalk-server/package.json")
        return try {
            JSONObject(pkg.readText()).optString("version").ifEmpty { null }
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        }
    }

    /** What has happened on this phone so far; empty when the file is missing or unreadable. */
    fun history(): InstallHistory = synchronized(LOCK) { readHistory() }

    /**
     * signalk-server's published versions, newest first, from `npm view signalk-server versions`,
     * remembered for [maxAgeMs] in `files/sk/versions.json` so the Updates card does not ask the
     * registry every time it is opened. Throws [IOException] when npm cannot answer and nothing
     * is remembered.
     */
    @Throws(IOException::class)
    fun listAvailable(withPrereleases: Boolean = false, maxAgeMs: Long = VERSIONS_MAX_AGE_MS): List<ServerVersion> {
        val cached = readVersions()
        val texts = if (cached != null && System.currentTimeMillis() - cached.first < maxAgeMs) {
            cached.second
        } else {
            try {
                fetchVersions().also { writeVersions(it) }
            } catch (e: IOException) {
                cached?.second ?: throw e
            }
        }
        return ServerVersion.newestFirst(texts, withPrereleases)
    }

    /**
     * Installs from [source] into the inactive slot, smoke-tests it and promotes it; returns the
     * final [State.Done] or [State.Failed], having told [listener] along the way. A version the
     * health gate rolled back before ([InstallHistory.isBad]) is refused unless [allowBad]. The
     * live slot is touched only by the promotion, and not at all when anything fails.
     */
    fun install(source: Source, allowBad: Boolean = false, listener: Listener): State = synchronized(LOCK) {
        Run(source, allowBad, listener).execute()
    }

    /**
     * Makes the other slot live again: the health gate's way out ([bad] true, the version left is
     * marked [InstallHistory.Outcome.BAD]) and the Updates card's "Rollback to …" ([bad] false).
     * Returns the slot now live, or null when there is nothing to go back to: no live slot, an
     * empty other slot, or a version there that was itself rolled back as bad.
     *
     * [onlyFrom] is the version the caller means to leave, checked under the same lock as an
     * install: the health gate's verdict is about one version, and an install may have made
     * another live while the rollback waited for it. That newer version is then left alone,
     * not marked bad for its predecessor's failure, and null is returned.
     */
    fun rollback(bad: Boolean, reason: String? = null, onlyFrom: String? = null): String? = synchronized(LOCK) {
        val live = liveSlot() ?: return null
        if (onlyFrom != null && installedVersion(live) != onlyFrom) return null
        val target = Slots.inactive(live)
        val targetVersion = installedVersion(target) ?: return null
        val history = readHistory()
        if (history.isBad(targetVersion)) return null
        promote(target)
        val outcome = if (bad) InstallHistory.Outcome.BAD else InstallHistory.Outcome.ROLLED_BACK
        writeHistory(
            history + InstallHistory.Entry(
                System.currentTimeMillis(), installedVersion(live) ?: "?", live, SOURCE_ROLLBACK, outcome,
                reason ?: "back to $targetVersion in slot $target",
            ),
        )
        target
    }

    /** One install, from the wipe to the record; the tail and its throttle are its own. */
    private inner class Run(private val source: Source, private val allowBad: Boolean, private val listener: Listener) {
        private val tail = ArrayDeque<String>()
        private var lastPost = 0L
        private val slot = Slots.inactive(liveSlot())

        /** True from the smoke test on, so the output that follows is reported as [State.Verifying]. */
        private var verifying = false

        /** True once the slot has been wiped for this install, so a failure empties it again. */
        private var wiped = false

        /** The version made live, once it is: from then on nothing is a failure of this install. */
        private var promoted: String? = null

        fun execute(): State {
            val state = try {
                attempt()
            } catch (e: Exception) {
                // Broadly: whatever went wrong, it is recorded and reported, not thrown at a screen.
                // After the promotion the install has happened, whatever the log does (closing it
                // can still throw on a full disk), so it is reported as done.
                promoted?.let { State.Done(it, slot, readHistory()) } ?: fail("$e")
            }
            listener.onState(state)
            return state
        }

        private fun attempt(): State {
            (source as? Source.Registry)?.version?.let { if (!allowBad && readHistory().isBad(it)) return refused(it) }
            runtime.ensureExtracted()
            val dir = slotDir(slot)
            wiped = true
            dir.deleteRecursively()
            if (!dir.mkdirs()) throw IOException("could not create $dir")
            logFile.parentFile?.mkdirs()
            return logFile.bufferedWriter().use { log ->
                val spec = when (source) {
                    is Source.Registry -> source.version?.let { "$PACKAGE@$it" } ?: PACKAGE
                    is Source.Tarball -> source.file.absolutePath
                }
                line(log, "$ npm install -g --prefix $dir $spec ${NPM_ARGS.joinToString(" ")}", now = true)
                val code = runtime.npm("install", "-g", "--prefix", dir.absolutePath, spec, *NPM_ARGS)
                    .drain(INSTALL_TIMEOUT_S) { line(log, it, now = false) }
                when {
                    code == null -> return@use fail("npm ran out of time after ${INSTALL_TIMEOUT_S / 60} min", log)
                    code != 0 -> return@use fail("npm failed with exit code $code", log)
                }
                val version = installedVersion(slot) ?: return@use fail("npm finished, but signalk-server is not in slot $slot", log)
                if (!allowBad && readHistory().isBad(version)) return@use refused(version, log)
                verifying = true
                listener.onState(State.Verifying(slot, tailText()))
                smokeTest(dir, log)?.let { return@use fail(it, log, version) }
                promote(slot)
                // Live from here on: nothing that fails afterwards may empty the slot.
                wiped = false
                promoted = version
                record(version, InstallHistory.Outcome.PROMOTED, null)
                line(log, "signalk-server $version is live in slot $slot", now = true)
                State.Done(version, slot, readHistory())
            }
        }

        /**
         * Starts the new server on [SMOKE_PORT] with a copy of the live configuration and sample
         * data, and waits for its discovery document; null when it answered, otherwise why not.
         * The copy has mDNS and SSL off: the test server must neither announce a second Signal K
         * server on the boat's network nor move to its HTTPS port, and every listener it has
         * moves to a port of its own, beside the live server's.
         */
        private fun smokeTest(dir: File, log: Writer): String? {
            val verify = File(app.cacheDir, "verify")
            verify.deleteRecursively()
            try {
                copyConfig(verify, log)?.let { return it }
                quietSettings(File(verify, "settings.json"), log)
                line(log, "$ PORT=$SMOKE_PORT signalk-server --sample-nmea0183-data   # smoke test", now = true)
                val node = runtime.spawn(
                    runtime.serverMain(dir).absolutePath, "--sample-nmea0183-data",
                    extraEnv = mapOf(
                        "SIGNALK_NODE_CONFIG_DIR" to verify.absolutePath,
                        "PORT" to "$SMOKE_PORT",
                        "TCPSTREAMPORT" to "$SMOKE_TCP_PORT",
                        "NMEA0183PORT" to "$SMOKE_NMEA_PORT",
                    ),
                )
                val p = node.process
                // Read while the poll runs, or a chatty server fills its pipe and stalls.
                val reader = thread(name = "install-smoke-log", isDaemon = true) {
                    try {
                        p.inputStream.bufferedReader().forEachLine { synchronized(log) { line(log, it, now = false) } }
                    } catch (_: IOException) {
                        // The stream closes under the reader when the group is killed.
                    }
                }
                val client = SignalKClient(app, "http://127.0.0.1:$SMOKE_PORT", null)
                val started = System.nanoTime()
                var answered = false
                while (p.isAlive && System.nanoTime() - started < SMOKE_TIMEOUT_NS) {
                    if (client.endpoints() is SignalKClient.Result.Ok) {
                        answered = true
                        break
                    }
                    Thread.sleep(POLL_MS)
                }
                val ms = (System.nanoTime() - started) / 1_000_000
                val exited = !p.isAlive
                node.terminate()
                if (!p.waitFor(STOP_TIMEOUT_S, TimeUnit.SECONDS)) node.kill()
                p.waitFor()
                node.reap()
                reader.join(DRAIN_MS)
                return synchronized(log) {
                    when {
                        answered -> {
                            line(log, "(smoke test: answered on :$SMOKE_PORT after $ms ms)", now = true)
                            null
                        }
                        exited -> "the new server exited with code ${p.exitValue()} during its smoke test"
                        else -> "the new server did not answer on :$SMOKE_PORT within ${SMOKE_TIMEOUT_NS / 1_000_000_000} s"
                    }
                }
            } finally {
                verify.deleteRecursively()
            }
        }

        /**
         * The live configuration, plugins and all, copied with the system's own `cp -a` so its
         * symlinks (`node_modules/.bin`) stay links. A phone with no configuration yet gets an
         * empty one, as a first start would.
         */
        private fun copyConfig(verify: File, log: Writer): String? {
            val live = runtime.configDir
            if (live.list().isNullOrEmpty()) {
                return if (verify.mkdirs()) null else "could not create $verify"
            }
            // Its few lines of output go to a file, not a pipe, so its deadline holds even if it hangs.
            val out = File(app.cacheDir, "verify-cp.txt")
            try {
                val cp = ProcessBuilder(CP, "-a", live.absolutePath, verify.absolutePath)
                    .redirectErrorStream(true)
                    .redirectOutput(out)
                    .start()
                val finished = cp.waitFor(COPY_TIMEOUT_S, TimeUnit.SECONDS)
                if (!finished) {
                    cp.destroyForcibly()
                    cp.waitFor()
                }
                if (out.isFile) out.forEachLine { line(log, it, now = false) }
                return when {
                    !finished -> "copying the configuration for the smoke test ran out of time"
                    cp.exitValue() != 0 -> "copying the configuration for the smoke test failed (cp exit ${cp.exitValue()})"
                    else -> null
                }
            } finally {
                out.delete()
            }
        }

        private fun quietSettings(settings: File, log: Writer) {
            val json = try {
                if (settings.isFile) JSONObject(settings.readText()) else JSONObject()
            } catch (_: JSONException) {
                // The test then runs on the server's defaults, still a test of the new version.
                line(log, "(settings.json does not parse; the smoke test uses defaults)", now = true)
                JSONObject()
            }
            json.put("mdns", false).put("ssl", false)
            settings.writeText(json.toString(2))
        }

        private fun refused(version: String, log: Writer? = null): State =
            fail("signalk-server $version was rolled back on this phone before; install it anyway to try again", log, version)

        private fun fail(reason: String, log: Writer? = null, version: String? = null): State {
            log?.let { line(it, "failed: $reason", now = true) }
            if (wiped) slotDir(slot).deleteRecursively()
            record(version ?: (source as? Source.Registry)?.version ?: "?", InstallHistory.Outcome.FAILED, reason)
            return State.Failed(reason, tailText())
        }

        private fun record(version: String, outcome: InstallHistory.Outcome, detail: String?) {
            val from = when (source) {
                is Source.Registry -> SOURCE_REGISTRY
                is Source.Tarball -> source.file.name
            }
            try {
                writeHistory(readHistory() + InstallHistory.Entry(System.currentTimeMillis(), version, slot, from, outcome, detail))
            } catch (_: IOException) {
                // A history that cannot be written loses one line, not the install.
            }
        }

        private fun line(log: Writer, text: String, now: Boolean) {
            log.appendLine(text)
            tail.addLast(text)
            while (tail.size > TAIL_LINES) tail.removeFirst()
            val t = System.nanoTime()
            if (!now && t - lastPost < UPDATE_INTERVAL_NS) return
            lastPost = t
            log.flush()
            listener.onState(if (verifying) State.Verifying(slot, tailText()) else State.Installing(slot, tailText()))
        }

        private fun tailText() = tail.joinToString("\n")
    }

    private fun slotDir(slot: String) = File(runtime.slots, slot)

    /** Points `current` at [slot] in one rename over the old link, never a moment without one. */
    private fun promote(slot: String) {
        val next = File(sk, "current.new")
        next.delete()
        Os.symlink(Slots.linkTarget(slot), next.absolutePath)
        Os.rename(next.absolutePath, runtime.current.absolutePath)
    }

    private fun fetchVersions(): List<String> {
        runtime.ensureExtracted()
        val out = StringBuilder()
        val code = runtime.npm("view", PACKAGE, "versions", "--json").drain(VIEW_TIMEOUT_S) { out.appendLine(it) }
        if (code != 0) throw IOException(if (code == null) "npm view ran out of time" else "npm view failed with exit code $code")
        return try {
            JSONArray(out.toString()).let { a -> List(a.length()) { a.getString(it) } }
        } catch (e: JSONException) {
            throw IOException("npm view gave no version list", e)
        }
    }

    private fun readVersions(): Pair<Long, List<String>>? = try {
        val json = JSONObject(versionsFile.readText())
        val a = json.getJSONArray("versions")
        json.getLong("fetchedAt") to List(a.length()) { a.getString(it) }
    } catch (_: IOException) {
        null
    } catch (_: JSONException) {
        null
    }

    private fun writeVersions(versions: List<String>) {
        val json = JSONObject().put("fetchedAt", System.currentTimeMillis()).put("versions", JSONArray(versions))
        writeAtomically(versionsFile, json.toString())
    }

    /**
     * The history file, entry by entry: one entry this build cannot read (a newer build's outcome,
     * a damaged line) is left out alone, because the next write would otherwise replace the whole
     * file with what was read, BAD markers and all.
     */
    private fun readHistory(): InstallHistory {
        val a = try {
            JSONArray(historyFile.readText())
        } catch (_: IOException) {
            return InstallHistory()
        } catch (_: JSONException) {
            return InstallHistory()
        }
        return InstallHistory(
            (0 until a.length()).mapNotNull { i ->
                try {
                    val e = a.getJSONObject(i)
                    InstallHistory.Entry(
                        at = e.getLong("at"),
                        version = e.getString("version"),
                        slot = e.getString("slot"),
                        source = e.getString("source"),
                        outcome = InstallHistory.Outcome.valueOf(e.getString("outcome")),
                        detail = e.optString("detail").ifEmpty { null },
                    )
                } catch (_: JSONException) {
                    null
                } catch (_: IllegalArgumentException) {
                    // An outcome this build does not know, from a newer one.
                    null
                }
            },
        )
    }

    private fun writeHistory(history: InstallHistory) {
        val a = JSONArray()
        for (e in history.entries) {
            a.put(
                JSONObject()
                    .put("at", e.at)
                    .put("version", e.version)
                    .put("slot", e.slot)
                    .put("source", e.source)
                    .put("outcome", e.outcome.name)
                    .apply { e.detail?.let { put("detail", it) } },
            )
        }
        writeAtomically(historyFile, a.toString(2))
    }

    /** Written beside and renamed over, so a killed app leaves the old file or the new one. */
    private fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, "${file.name}.part")
        part.writeText(text)
        if (!part.renameTo(file)) throw IOException("could not write $file")
    }

    companion object {
        const val PACKAGE = "signalk-server"

        /** Where the smoke test answers, beside the live server's 3000 (§5.2). */
        const val SMOKE_PORT = 3999

        /** The test server's Signal K TCP stream and NMEA 0183 TCP ports, beside the live 8375 and 10110. */
        const val SMOKE_TCP_PORT = 8376
        const val SMOKE_NMEA_PORT = 10111

        /** Global, as on a Pi (§5.1); scripts in the foreground so their output reaches the log. */
        private val NPM_ARGS = arrayOf("--omit=dev", "--no-audit", "--no-fund", "--foreground-scripts")

        private const val SOURCE_REGISTRY = "npm"
        private const val SOURCE_ROLLBACK = "rollback"
        private const val CP = "/system/bin/cp"
        private const val INSTALL_TIMEOUT_S = 20 * 60L
        private const val VIEW_TIMEOUT_S = 60L
        private const val COPY_TIMEOUT_S = 5 * 60L
        private const val SMOKE_TIMEOUT_NS = 60_000_000_000L
        private const val STOP_TIMEOUT_S = 10L
        private const val POLL_MS = 1_000L
        private const val DRAIN_MS = 2_000L
        private const val TAIL_LINES = 300
        private const val UPDATE_INTERVAL_NS = 500_000_000L
        const val VERSIONS_MAX_AGE_MS = 24 * 60 * 60_000L

        /** One install or rollback at a time in the process: both rewrite the slots and `current`. */
        private val LOCK = Any()
    }
}
