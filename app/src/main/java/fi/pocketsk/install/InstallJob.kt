package fi.pocketsk.install

import fi.pocketsk.net.Failure
import fi.pocketsk.net.transportThread
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The one [InstallManager.install] running in this process, if any, and how it went: held here,
 * not by a screen, because an install takes minutes and a rotation, the wizard's next page or
 * the Dock's Updates card must all see the same one (docs/PLAN.md §5.3, §8.1). A second start
 * while one runs is refused, and the screen that asks [observe]s the running one instead.
 * Every screen alive observes, not only the one in front: the Dock stays alive under the
 * runtime checks, and an install finishing then must still reach it.
 */
internal object InstallJob {

    /** A screen's view of the install. Called on the installing thread, or the observing one at first. */
    fun interface Observer {
        fun onState(state: InstallManager.State, running: Boolean)
    }

    private val observers = CopyOnWriteArraySet<Observer>()
    @Volatile private var latest: InstallManager.State? = null
    @Volatile var isRunning = false
        private set

    /**
     * Starts installing [source] with [manager] on a thread of its own; false, and nothing
     * started, when an install is already running. Both callbacks run on the installing thread,
     * whether or not a screen is still watching: [onPromoting] with the version just before it is
     * made live, under the installer's lock ([InstallManager.install] says why), and
     * [onPromoted] once it is, before any observer hears, so the supervisor can move to it.
     */
    @Synchronized
    fun start(
        manager: InstallManager,
        source: InstallManager.Source,
        allowBad: Boolean = false,
        onPromoting: (String) -> Unit = {},
        onPromoted: (InstallManager.State.Done) -> Unit = {},
    ): Boolean {
        if (isRunning) return false
        isRunning = true
        latest = null
        transportThread("install", onError = {
            // What escaped the install (onPromoted, say): on this worker thread, logged before it is shown.
            manager.noteFailure(it)
            finish(InstallManager.State.Failed(Failure.text(it), ""))
        }) {
            val end = manager.install(source, allowBad, onPromoting) { state -> publish(state, running = true) }
            if (end is InstallManager.State.Done) onPromoted(end)
            finish(end)
        }
        return true
    }

    /** Adds [observer] to those told, and tells it at once what is known so far. */
    @Synchronized
    fun observe(observer: Observer) {
        observers += observer
        latest?.let { observer.onState(it, isRunning) }
    }

    /** Stops telling [observer]. */
    fun detach(observer: Observer) {
        observers -= observer
    }

    @Synchronized
    private fun finish(state: InstallManager.State) {
        isRunning = false
        publish(state, running = false)
    }

    @Synchronized
    private fun publish(state: InstallManager.State, running: Boolean) {
        latest = state
        for (o in observers) o.onState(state, running)
    }
}
