package fi.pocketsk.server

import android.content.Context
import android.os.SystemClock
import fi.pocketsk.install.HealthGate
import fi.pocketsk.install.InstallManager
import fi.pocketsk.install.Slots
import fi.pocketsk.net.Waiter
import fi.pocketsk.net.transportThread
import fi.pocketsk.node.NodeProcess
import fi.pocketsk.node.NodeRuntime
import fi.pocketsk.signalk.SignalKClient
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs the live signalk-server and keeps it running (docs/PLAN.md §6): the part of
 * [SignalKService] that knows nothing of notifications or locks.
 *
 * One `sk-supervisor` thread runs the server, `node <slot>/lib/node_modules/signalk-server/bin/
 * signalk-server`, from the slot `files/sk/current` points at when it starts, so a promotion
 * during a run changes nothing until the next one. Per run:
 *
 * - its output goes to [ServerLog] on an `sk-server-log` thread;
 * - an `sk-health` thread polls `GET /signalk` once a second until it answers, then every
 *   [HEALTH_POLL_MS] (§6's 30 s), and feeds the [HealthGate] while one is open; on the port
 *   `settings.json` names, read before the run, with SSL's redirect as the answer when SSL is
 *   on ([ServerEndpoint]), so a server moved off Signal K's defaults in the admin UI is not
 *   taken for a dead one and rolled back;
 * - when it ends, whatever is left of its process group is killed ([NodeProcess.reap]) before
 *   anything else, so no plugin's helper holds a port or a serial device against the next run.
 *
 * An exit nobody asked for goes to [RestartPolicy]; an exit asked for ([stop], [restart], a
 * failed gate) does not. A stop is SIGTERM to the group and SIGKILL [STOP_TIMEOUT_S] later if
 * the server is still there.
 *
 * The gate: [promoted] (from the installer) or [ServerPrefs.gateVersion] (a gate the process
 * died in) opens one when that version's first run starts. Three crashes in five minutes or no
 * answer within 90 s fail it: [InstallManager.rollback] makes the other slot live, marks this
 * version bad, and the server starts again from there. That call waits for an install in
 * progress to finish, since both rewrite `current`.
 *
 * Everything reported goes through [onState]; [onAlert] is for what the user should hear about
 * even with no screen open: a rollback, or giving up (with the [ServerState.Failed] it ends in).
 * Both are called on the supervisor's threads.
 */
internal class Supervisor(
    context: Context,
    private val runtime: NodeRuntime,
    private val log: ServerLog,
    private val prefs: ServerPrefs,
    private val onState: (ServerState) -> Unit,
    private val onAlert: (String, ServerState.Failed?) -> Unit,
) {
    private val app = context.applicationContext
    private val installs = InstallManager(app, runtime)
    private val policy = RestartPolicy()
    private val waiter = Waiter()

    /** Why the run in progress is being ended, if anyone asked. */
    private enum class Ask { NONE, STOP, RESTART, ROLLBACK }

    // All guarded by `this`.
    private var loop: Thread? = null
    private var wanted = false
    private var node: NodeProcess? = null
    private var ask = Ask.NONE
    private var gate: HealthGate? = null
    private var gateFor: String? = null
    private var rollbackFirst = false
    private var gateFailure: String? = null

    /** The slot of the run in progress, set with [node]. */
    private var runSlot: String? = null

    @Volatile var state: ServerState = ServerState.Stopped
        private set

    /** True while the supervisor thread exists: running, starting, waiting or stopping. */
    val isActive: Boolean @Synchronized get() = loop != null

    /**
     * Starts the server unless it runs; one waiting out a backoff starts at once. [rollbackFirst]
     * makes the other slot live before the start (the "Roll back" of a server that gave up).
     */
    @Synchronized
    fun start(rollbackFirst: Boolean = false) {
        wanted = true
        if (rollbackFirst) this.rollbackFirst = true
        if (loop != null) {
            if (state is ServerState.Waiting) waiter.wake()
            return
        }
        launch()
    }

    /** Stops the server and keeps it stopped. */
    @Synchronized
    fun stop() {
        wanted = false
        end(Ask.STOP)
    }

    /** Stops the server and starts it again, without counting it against the restart policy. */
    @Synchronized
    fun restart() {
        if (loop == null) return start()
        wanted = true
        end(Ask.RESTART)
    }

    /**
     * A new version was made live ([ServerPrefs.gateVersion] says which): a running server moves
     * to it now, under the health gate; a stopped one meets the gate on its next start. A run of
     * the live slot already (a gate rollback that found the new version live and started it) is
     * left be.
     */
    @Synchronized
    fun promoted() {
        if (loop == null) return
        val live = installs.liveSlot()
        if (node != null && live != null && live == runSlot) return
        restart()
    }

    /** The service is going: kill the group now, without the grace of [stop]. */
    @Synchronized
    fun shutdown() {
        wanted = false
        ask = Ask.STOP
        waiter.wake()
        node?.kill()
    }

    private fun end(why: Ask) {
        ask = why
        val n = node
        if (n == null) {
            // Between runs: waiting out a backoff, or about to spawn, which checks [ask] first.
            waiter.wake()
            return
        }
        publish(ServerState.Stopping)
        terminate(n)
    }

    /** SIGTERM to the group, SIGKILL [STOP_TIMEOUT_S] later if the server has not ended. */
    private fun terminate(n: NodeProcess) {
        n.terminate()
        thread(name = "sk-stop", isDaemon = true) {
            if (!n.process.waitFor(STOP_TIMEOUT_S, TimeUnit.SECONDS)) {
                log.note("still running ${STOP_TIMEOUT_S}s after SIGTERM: killing its process group")
                n.kill()
            }
        }
    }

    /** Under `this`. */
    private fun launch() {
        policy.reset()
        loop = transportThread("sk-supervisor", onError = { e ->
            log.note("supervisor failed: $e")
            synchronized(this) {
                wanted = false
                node?.kill()
            }
            val failed = ServerState.Failed("$e", rollbackTarget())
            onAlert("$e", failed)
            finished(failed)
        }) {
            finished(runLoop())
        }
    }

    /**
     * The loop has returned [last]: report it, unless a start came in after the loop decided to
     * end (a start that found the old loop still there only set [wanted]), which then gets a
     * loop of its own. Every way the loop gives up clears [wanted] first, so a set one here is
     * always a new request. Published under the lock, so a start right after cannot have its
     * Starting overtaken by this.
     */
    @Synchronized
    private fun finished(last: ServerState) {
        loop = null
        node = null
        if (wanted) launch() else publish(last)
    }

    /** Runs the server until it is stopped or given up on; returns the state to end in. */
    private fun runLoop(): ServerState {
        while (true) {
            val rollBack = synchronized(this) {
                if (!wanted) return ServerState.Stopped
                ask = Ask.NONE
                rollbackFirst.also { rollbackFirst = false }
            }
            if (rollBack) {
                val to = installs.rollback(bad = false, reason = "by hand, after the server gave up")
                log.note(to?.let { "rolled back by hand: slot $it is live" } ?: "nothing to roll back to")
                policy.reset()
            }
            val slot = installs.liveSlot()
            val version = slot?.let { installs.installedVersion(it) }
            if (slot == null || version == null) return failed("signalk-server is not installed")
            val main = runtime.serverMain(File(runtime.slots, slot))
            val trial = armGate(version)
            val endpoint = readEndpoint()
            publish(ServerState.Starting(version, slot))
            log.note(
                "starting signalk-server $version from slot $slot, HTTP on port ${endpoint.port}" +
                    (if (endpoint.ssl) " (redirecting to HTTPS)" else "") +
                    if (trial) ", on trial after its promotion" else "",
            )
            // A wake left over from a stop that came between runs would cut the next backoff short.
            waiter.await(0)
            val startedAt = SystemClock.elapsedRealtime()
            val n = try {
                runtime.spawn(main.absolutePath)
            } catch (e: IOException) {
                return failed("could not start node: $e")
            }
            synchronized(this) {
                node = n
                runSlot = slot
                // A stop or restart that came while this was spawning found no node to signal.
                if (ask != Ask.NONE) {
                    publish(ServerState.Stopping)
                    terminate(n)
                }
            }
            val reader = thread(name = "sk-server-log") {
                try {
                    n.process.inputStream.bufferedReader().forEachLine(log::line)
                } catch (e: IOException) {
                    // Closed under it when the group was reaped, or a read error: a server nobody
                    // reads fills its pipe and stalls, so it goes when its reader does.
                    if (n.process.isAlive) {
                        log.note("reading its output failed: $e")
                        n.kill()
                    }
                }
            }
            val health = thread(name = "sk-health", isDaemon = true) { watch(n, version, slot, endpoint) }
            val code = n.process.waitFor()
            val whole = n.reap()
            health.interrupt()
            reader.join(DRAIN_MS)
            val now = SystemClock.elapsedRealtime()
            val why = synchronized(this) {
                node = null
                ask
            }
            log.note(
                "exit $code after ${(now - startedAt) / 1000} s" +
                    if (whole) "" else " (no process group: anything it started may still run)",
            )
            when (why) {
                Ask.STOP, Ask.RESTART -> continue
                Ask.ROLLBACK -> {
                    rollBackBad(version, synchronized(this) { gateFailure } ?: "failed its health gate")
                    continue
                }
                Ask.NONE -> Unit
            }
            val failedGate = synchronized(this) {
                gate?.let {
                    it.crashed(now)
                    it.verdict(now) == HealthGate.Verdict.FAILED
                } == true
            }
            if (failedGate) {
                rollBackBad(version, "crashed ${HealthGate.CRASH_LIMIT} times within its health gate")
                continue
            }
            when (val d = policy.exited(code, startedAt, now)) {
                RestartPolicy.Decision.GiveUp -> return failed(
                    "exited ${RestartPolicy.FAILURE_LIMIT} times within ${RestartPolicy.WINDOW_MS / 60_000} min, the last with $code",
                )
                is RestartPolicy.Decision.Restart -> if (d.delayMs > 0) {
                    log.note("starting again in ${d.delayMs / 1000} s")
                    publish(ServerState.Waiting(version, d.delayMs, "exit $code"))
                    waiter.await(d.delayMs)
                }
            }
        }
    }

    /**
     * The health watch of one run: once a second until the server answers, then every
     * [HEALTH_POLL_MS]; publishes [ServerState.Running] whenever the answer changes, and feeds
     * the gate. A failed gate ends the run, and [runLoop] rolls back.
     */
    private fun watch(n: NodeProcess, version: String, slot: String, endpoint: ServerEndpoint) {
        val base = "http://127.0.0.1:${endpoint.port}"
        val client = SignalKClient(app, base, null)
        var answering: Boolean? = null
        var trial = false
        while (n.process.isAlive) {
            val ok = when (val r = client.endpoints()) {
                is SignalKClient.Result.Ok -> true
                is SignalKClient.Result.Failed -> endpoint.alive(ok = false, httpCode = r.httpCode)
            }
            val now = SystemClock.elapsedRealtime()
            // Decided and published under the lock, so a stop that has published Stopping is
            // never followed by a Running from a poll that was already under way.
            synchronized(this) {
                if (node !== n || ask != Ask.NONE) return
                val g = gate
                if (ok) g?.answered(now)
                when (g?.verdict(now)) {
                    HealthGate.Verdict.FAILED -> {
                        gateFailure = "did not answer within ${HealthGate.ANSWER_WITHIN_MS / 1000} s of its promotion"
                        ask = Ask.ROLLBACK
                        publish(ServerState.Stopping)
                        terminate(n)
                        return
                    }
                    HealthGate.Verdict.HEALTHY -> {
                        gate = null
                        gateFor = null
                        prefs.settle(version)
                        log.note("signalk-server $version passed its health gate")
                    }
                    HealthGate.Verdict.PENDING, null -> Unit
                }
                val onTrial = gate != null
                // Nothing before the first answer: until then the run is Starting.
                if ((ok || answering != null) && (ok != answering || onTrial != trial)) {
                    if (answering == true && !ok) log.note("not answering on $base")
                    publish(ServerState.Running(version, slot, endpoint.port, ok, onTrial))
                    answering = ok
                    trial = onTrial
                }
            }
            try {
                Thread.sleep(if (answering != null) HEALTH_POLL_MS else FIRST_POLL_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /**
     * Opens the health gate for [version] if it owes one ([ServerPrefs.gateVersion]) and none is
     * open for it. True when the coming run is on trial.
     *
     * A gate owed by another version is left where it is, never cleared here: the slot this run
     * starts from was read without the installer's lock (holding it would hold every start for
     * as long as an install runs), so it may be the slot a promotion is just moving away from,
     * and the version that owes the gate starts next, when [promoted] restarts the run. Only that
     * version's own gate verdict, or a rollback of it, clears what it owes.
     */
    private fun armGate(version: String): Boolean = synchronized(this) {
        if (prefs.gateVersion != version) {
            gate = null
            gateFor = null
            return false
        }
        if (gateFor != version) {
            gate = HealthGate(promotedAt = SystemClock.elapsedRealtime())
            gateFor = version
        }
        true
    }

    /**
     * The gate failed for [version]: back to the other slot, the version marked bad. Only while
     * [version] is still live: an install that finished while the rollback waited for it has made
     * another version live, which keeps its own pending gate and is started next, on trial.
     */
    private fun rollBackBad(version: String, why: String) {
        synchronized(this) {
            if (gateFor == version) {
                gate = null
                gateFor = null
            }
            gateFailure = null
        }
        prefs.settle(version)
        val to = installs.rollback(bad = true, reason = why, onlyFrom = version)
        if (to == null) {
            val live = installs.liveSlot()?.let { installs.installedVersion(it) }
            log.note(
                if (live != version) {
                    "signalk-server $version $why, but $live has been made live since: starting that"
                } else {
                    "signalk-server $version $why, and there is nothing to roll back to"
                },
            )
            return
        }
        policy.reset()
        val back = installs.installedVersion(to) ?: "?"
        log.note("signalk-server $version $why: rolled back to $back in slot $to")
        onAlert("signalk-server $version $why. Back to $back.", null)
    }

    private fun failed(reason: String): ServerState {
        log.note("giving up: $reason")
        synchronized(this) { wanted = false }
        val state = ServerState.Failed(reason, rollbackTarget())
        onAlert(reason, state)
        return state
    }

    /**
     * The live configuration's HTTP port and SSL setting; Signal K's defaults when there is no
     * `settings.json` yet (a first start) or it cannot be read, which is what the server then
     * falls back to as well.
     */
    private fun readEndpoint(): ServerEndpoint = try {
        val json = JSONObject(File(runtime.configDir, "settings.json").readText())
        ServerEndpoint.of(json.opt("port"), json.opt("ssl"))
    } catch (_: IOException) {
        ServerEndpoint.DEFAULT
    } catch (_: JSONException) {
        ServerEndpoint.DEFAULT
    }

    /** The version a rollback would make live, when there is one that was not rolled back as bad. */
    private fun rollbackTarget(): String? {
        val live = installs.liveSlot() ?: return null
        val version = installs.installedVersion(Slots.inactive(live)) ?: return null
        return version.takeUnless { installs.history().isBad(it) }
    }

    private fun publish(state: ServerState) {
        this.state = state
        onState(state)
    }

    companion object {
        const val HEALTH_POLL_MS = 30_000L
        private const val FIRST_POLL_MS = 1_000L
        private const val STOP_TIMEOUT_S = 10L

        /** How long the exit waits for the last of the output once the group is gone. */
        private const val DRAIN_MS = 2_000L
    }
}
