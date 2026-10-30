package fi.pocketsk.server

/** What the supervisor is doing with the server, for the notification and the screens (docs/PLAN.md §6). */
internal sealed interface ServerState {
    /** Not running, and not asked to. */
    data object Stopped : ServerState

    /** Started from [slot], not answering `GET /signalk` yet. */
    data class Starting(val version: String, val slot: String) : ServerState

    /**
     * Up, its plain HTTP on [port] ([ServerEndpoint]). [answering] is the last health poll's
     * result; [onTrial] is true while a freshly promoted version is still inside its health gate
     * and may yet be rolled back. [startedAt] is when this run's node was started
     * (SystemClock.elapsedRealtime), and [group] its process group ([fi.pocketsk.node.NodeProcess]),
     * null where there is none: the Dock's uptime, CPU and memory. [lan] is whether this run
     * listens on the boat's network or on 127.0.0.1 alone, as LAN access was when it started.
     */
    data class Running(
        val version: String,
        val slot: String,
        val port: Int,
        val answering: Boolean,
        val onTrial: Boolean,
        val startedAt: Long,
        val group: Int?,
        val lan: Boolean,
    ) : ServerState

    /** Exited unasked, [reason], and starts again after [delayMs]. */
    data class Waiting(val version: String, val delayMs: Long, val reason: String) : ServerState

    /**
     * Not started yet because of the card the data lives on ([DataStore]): [waiting] for the card
     * [volume] to be put in (or to come back after it was taken out), or moving the data folders
     * to [volume], or to the phone when [volume] is null.
     */
    data class Storage(val waiting: Boolean, val volume: String?) : ServerState

    /** Asked to stop or restart; the group has had SIGTERM and gets SIGKILL after 10 s. */
    data object Stopping : ServerState

    /**
     * Given up: [reason] says why. [rollbackTo] is the version in the other slot when going back
     * to it is possible, which the notification then offers.
     */
    data class Failed(val reason: String, val rollbackTo: String?) : ServerState

    /** True while the supervisor holds the server or means to start it again. */
    val isActive: Boolean get() = this !is Stopped && this !is Failed
}
