package fi.pocketsk.server

import android.app.ActivityManager
import android.content.Context
import fi.pocketsk.install.InstallJob
import fi.pocketsk.net.Failure
import fi.pocketsk.net.transportThread
import fi.pocketsk.node.NodeRuntime
import fi.pocketsk.update.AppUpdater
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The Reset card's one reset at a time (docs/PLAN.md §16 15), held by the process like [BackupJob]
 * so a screen recreated meanwhile finds the one that runs.
 *
 * Either resets the server's settings ([ServerReset]): the server is stopped, `.signalk` goes back
 * to what a first start finds, the data kept, and a server that was running starts again as a new
 * one. Or resets everything: the server is stopped, the data on the phone's shared storage and on
 * every card is deleted, and Android clears the app's data as its own "Clear storage" does, which
 * ends the app; opened again, it starts with the setup, as just installed.
 *
 * The supervisor starts no server while a reset runs ([isResetting]), so none reads half of one.
 * Neither runs beside an install (its trial run copies `.signalk`) or a backup or restore.
 */
internal object ResetJob {

    enum class Kind { SETTINGS, EVERYTHING }

    sealed interface State {
        val kind: Kind

        /** Waiting for the server to stop. */
        data class Stopping(override val kind: Kind) : State

        data class Working(override val kind: Kind) : State

        /** The server's settings are reset ([removed] what went); [started] when it was running and was started again. */
        data class Done(val removed: Int, val started: Boolean) : State {
            override val kind = Kind.SETTINGS
        }

        data class Failed(override val kind: Kind, val why: Why, val detail: String = "") : State
    }

    enum class Why {
        /** An install, a backup or a restore runs: one thing at a time touches the server's files. */
        BUSY,

        /** The server did not stop in time; nothing was reset. */
        STILL_RUNNING,

        FAILED,
    }

    fun interface Observer {
        fun onState(state: State?)
    }

    private val observers = CopyOnWriteArraySet<Observer>()

    @Volatile var latest: State? = null
        private set

    val isRunning: Boolean get() = latest.let { it is State.Stopping || it is State.Working }

    /** A reset runs, from the moment it is asked for: the supervisor starts no server meanwhile. */
    val isResetting: Boolean get() = isRunning

    /** Resets the server's settings; false, nothing started, while another reset runs. */
    @Synchronized
    fun settings(context: Context): Boolean = begin(context, Kind.SETTINGS) { app ->
        val prefs = ServerPrefs(app)
        val wasRunning = prefs.running || ServerStatus.state.isActive
        stop(app)?.let { return@begin it }
        publish(State.Working(Kind.SETTINGS))
        val runtime = NodeRuntime(app)
        val log = ServerStatus.log(app)
        val removed = try {
            synchronized(DataStore.LOCK) {
                ServerReset.settings(runtime.configDir, mirror(app, prefs), BackupJob.restoredMarker(runtime))
            }
        } catch (e: IOException) {
            // Stopped for the reset, so started again for what remains: a file that could not be
            // deleted must not also leave the boat without its server.
            log.note("the reset failed: $e")
            if (wasRunning) start(app)
            return@begin State.Failed(Kind.SETTINGS, Why.FAILED, Failure.text(e))
        }
        log.note("the server's settings were reset: ${removed.joinToString().ifEmpty { "nothing to remove" }}")
        val started = wasRunning && start(app)
        State.Done(removed.size, started)
    }

    /**
     * Resets everything; false, nothing started, while another reset runs. On success the process
     * ends with Android's clear and no state follows.
     */
    @Synchronized
    fun everything(context: Context): Boolean = begin(context, Kind.EVERYTHING) { app ->
        stop(app)?.let { return@begin it }
        publish(State.Working(Kind.EVERYTHING))
        val log = ServerStatus.log(app)
        log.note("resetting everything: the server, its settings, its data and the app's own settings")
        // Android's clear covers the app's folders on external storage too; the data on a card is
        // deleted here first all the same, so a phone whose clear leaves a card's folder alone
        // does not keep the charts of a server it no longer has.
        for (dir in app.getExternalFilesDirs(null).filterNotNull() + app.externalCacheDirs.filterNotNull()) {
            try {
                ServerReset.deleteTree(dir.toPath())
            } catch (e: IOException) {
                log.note("could not delete ${dir.path}: $e")
            }
        }
        val am = app.getSystemService(ActivityManager::class.java)
        if (am?.clearApplicationUserData() == true) {
            // Android ends the process now; the state below is for a clear that has not yet.
            State.Working(Kind.EVERYTHING)
        } else {
            log.note("Android did not clear the app's data")
            State.Failed(Kind.EVERYTHING, Why.FAILED, Failure.UNKNOWN)
        }
    }

    fun observe(observer: Observer) {
        observers += observer
        observer.onState(latest)
    }

    fun detach(observer: Observer) {
        observers -= observer
    }

    private fun begin(context: Context, kind: Kind, body: (Context) -> State): Boolean {
        if (isRunning) return false
        val app = context.applicationContext
        // The app's update ends the process, and the reset with it.
        if (InstallJob.isRunning || BackupJob.isRunning || AppUpdater.updating(context)) {
            publish(State.Failed(kind, Why.BUSY))
            return true
        }
        publish(State.Stopping(kind))
        transportThread("reset", onError = { e ->
            ServerStatus.log(app).note("the reset failed: $e")
            publish(State.Failed(kind, Why.FAILED, Failure.text(e)))
        }) {
            publish(body(app))
        }
        return true
    }

    /** Stops the server and waits until it has; null once it has, the failure if it does not in time. */
    private fun stop(app: Context): State? {
        val kind = latest?.kind ?: Kind.SETTINGS
        SignalKService.stop(app)
        val until = System.nanoTime() + STOP_WAIT_NS
        while (ServerStatus.state.isActive) {
            if (System.nanoTime() > until) {
                ServerStatus.log(app).note("the reset gave up: the server did not stop")
                return State.Failed(kind, Why.STILL_RUNNING)
            }
            Thread.sleep(POLL_MS)
        }
        return null
    }

    /** Starts the reset server again; false when Android refuses a start now (the app is not in front). */
    private fun start(app: Context): Boolean = try {
        SignalKService.start(app)
        true
    } catch (e: IllegalStateException) {
        ServerStatus.log(app).note("the reset server could not be started from here: $e")
        false
    }

    /** The folder a computer sees, where the supervisor keeps the settings' copies, when it is there now. */
    private fun mirror(app: Context, prefs: ServerPrefs): File? =
        prefs.dataVolume?.let { DataVolumes.mounted(app, it)?.root } ?: if (prefs.dataVolume == null) DataVolumes.phone(app) else null

    @Synchronized
    private fun publish(state: State) {
        latest = state
        for (o in observers) o.onState(state)
    }

    private const val STOP_WAIT_NS = 60_000_000_000L
    private const val POLL_MS = 250L
}
