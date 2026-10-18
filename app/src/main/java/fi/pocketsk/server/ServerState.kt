package fi.pocketsk.server

/** What the supervisor is doing with the server, for the notification and the screens (docs/PLAN.md §6). */
internal sealed interface ServerState {
    /** Not running, and not asked to. */
    data object Stopped : ServerState

    /** Started from [slot], not answering `GET /signalk` yet. */
    data class Starting(val version: String, val slot: String) : ServerState

    /**
     * Up. [answering] is the last health poll's result; [onTrial] is true while a freshly
     * promoted version is still inside its health gate and may yet be rolled back.
     */
    data class Running(val version: String, val slot: String, val answering: Boolean, val onTrial: Boolean) : ServerState

    /** Exited unasked, [reason], and starts again after [delayMs]. */
    data class Waiting(val version: String, val delayMs: Long, val reason: String) : ServerState

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
