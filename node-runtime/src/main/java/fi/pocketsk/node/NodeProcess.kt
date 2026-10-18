package fi.pocketsk.node

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * A node that [NodeRuntime.spawn] started, with everything it starts in turn.
 *
 * The launcher puts the node it is asked to start into a process group of its own and writes
 * the group's id to [groupFile] before Node runs (native/launcher/main.cc). npm's lifecycle
 * scripts, a plugin's helpers and any node they run stay in that group, so [terminate] and
 * [kill] reach all of them; [process] alone reaches only the one pid it forked, and Android
 * gives an app no other handle on that pid's children. Killing npm used to leave its scripts
 * running, still writing into the slot (spike R3).
 *
 * When there is no group to signal, because the launcher has not written the file yet (it does
 * so before Node starts anything, so then there is nothing else to reach) or could not make the
 * group, both fall back to [process], and [hasGroup] says so.
 *
 * The id is a pid, and pids are reused once every process in the group has ended. So the group
 * is signalled only until [reap], which whoever waits for the process calls once it has ended:
 * it kills what is left of the group, then forgets it. After that both fall back to [process],
 * which has ended and ignores them.
 */
class NodeProcess internal constructor(val process: Process, private val groupFile: File) {
    private var group: Int? = null
    private var reaped = false

    /**
     * True when the process leads a group the app knows, so signals reach everything it started;
     * still true after [reap], so a caller can tell afterwards whether anything may have escaped.
     */
    val hasGroup: Boolean
        @Synchronized get() = group != null || groupId() != null

    /** SIGTERM to the whole group, which Signal K and npm both end on cleanly; true if it reached the group. */
    fun terminate(): Boolean = signal(OsConstants.SIGTERM) { process.destroy() }

    /** SIGKILL to the whole group; true if it reached the group. */
    fun kill(): Boolean = signal(OsConstants.SIGKILL) { process.destroyForcibly() }

    /**
     * Once [process] has ended: kills whatever it left running in its group (a script npm
     * started, a plugin's child), deletes [groupFile] and forgets the group. Idempotent. Returns
     * true when the group was known, so nothing the process started is left; false when only
     * the process itself could be stopped.
     */
    @Synchronized
    fun reap(): Boolean {
        val g = groupId()
        if (g != null) send(g, OsConstants.SIGKILL)
        if (!reaped) groupFile.delete()
        reaped = true
        return g != null
    }

    @Synchronized
    private fun signal(signal: Int, fallback: () -> Unit): Boolean {
        val g = groupId()
        if (g != null && send(g, signal)) return true
        fallback()
        return false
    }

    /** True when [signal] reached group [g], or the group has no member left to reach (ESRCH). */
    private fun send(g: Int, signal: Int): Boolean = try {
        Os.kill(-g, signal)
        true
    } catch (e: ErrnoException) {
        e.errno == OsConstants.ESRCH
    }

    /** The group's id, read once from [groupFile]; null after [reap] or while there is none. */
    private fun groupId(): Int? {
        if (reaped) return null
        group?.let { return it }
        val text = try {
            groupFile.readText()
        } catch (_: java.io.IOException) {
            return null
        }
        return parseGroupId(text).also { group = it }
    }

    companion object {
        /**
         * The launcher's record of its group, `<pid>\n`; null for anything else. Only an id above
         * 1, because the group is signalled as `kill(-id)`: 0 would signal the app's own group,
         * 1 every process the app may signal, itself included, and a negative id a single pid.
         */
        fun parseGroupId(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it > 1 }
    }
}
