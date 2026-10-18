package fi.pocketsk.install

import fi.pocketsk.net.transportThread

/**
 * The one [InstallManager.install] running in this process, if any, and how it went: held here,
 * not by a screen, because an install takes minutes and a rotation, the wizard's next page or
 * the Dock's Updates card must all see the same one (docs/PLAN.md §5.3, §8.1). A second start
 * while one runs is refused, and the screen that asks [observe]s the running one instead.
 */
internal object InstallJob {

    /** A screen's view of the install. Called on the installing thread, or the observing one at first. */
    fun interface Observer {
        fun onState(state: InstallManager.State, running: Boolean)
    }

    @Volatile private var observer: Observer? = null
    @Volatile private var latest: InstallManager.State? = null
    @Volatile var isRunning = false
        private set

    /**
     * Starts installing [source] with [manager] on a thread of its own; false, and nothing
     * started, when an install is already running. [onPromoted] is told, on the installing
     * thread and before any observer, when a version has been made live: what the supervisor
     * needs to move to it under its health gate, whether or not a screen is still watching.
     */
    @Synchronized
    fun start(
        manager: InstallManager,
        source: InstallManager.Source,
        allowBad: Boolean = false,
        onPromoted: (InstallManager.State.Done) -> Unit = {},
    ): Boolean {
        if (isRunning) return false
        isRunning = true
        latest = null
        transportThread("install", onError = { finish(InstallManager.State.Failed("$it", "")) }) {
            val end = manager.install(source, allowBad) { state -> publish(state, running = true) }
            if (end is InstallManager.State.Done) onPromoted(end)
            finish(end)
        }
        return true
    }

    /** Makes [observer] the one told, and tells it at once what is known so far. */
    @Synchronized
    fun observe(observer: Observer) {
        this.observer = observer
        latest?.let { observer.onState(it, isRunning) }
    }

    /** Stops telling [observer], unless another has taken its place already. */
    @Synchronized
    fun detach(observer: Observer) {
        if (this.observer === observer) this.observer = null
    }

    @Synchronized
    private fun finish(state: InstallManager.State) {
        isRunning = false
        publish(state, running = false)
    }

    @Synchronized
    private fun publish(state: InstallManager.State, running: Boolean) {
        latest = state
        observer?.onState(state, running)
    }
}
