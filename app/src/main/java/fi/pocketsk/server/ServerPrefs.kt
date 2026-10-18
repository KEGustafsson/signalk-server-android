package fi.pocketsk.server

import android.content.Context
import androidx.core.content.edit

/**
 * The supervisor's few settings and its memory across processes, in their own preferences file
 * (excluded from backups with every other one, data_extraction_rules.xml): whether the server
 * should be running, so a restart by the system ([android.app.Service.START_STICKY]) or after an
 * app update brings it back; whether it starts with the phone; and which promoted version still
 * owes its health gate, so a gate interrupted by the process dying is run on the next start.
 */
internal class ServerPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Started by someone and not stopped since. */
    var running: Boolean
        get() = prefs.getBoolean(RUNNING, false)
        set(value) = prefs.edit { putBoolean(RUNNING, value) }

    /** Start the server when the phone has booted. */
    var autostart: Boolean
        get() = prefs.getBoolean(AUTOSTART, false)
        set(value) = prefs.edit { putBoolean(AUTOSTART, value) }

    /** The version promoted last whose health gate (docs/PLAN.md §5.2 step 6) has not closed yet. */
    val gateVersion: String?
        get() = prefs.getString(GATE_VERSION, null)

    /**
     * [version] owes its health gate: the installer, just before it makes [version] live. Under
     * the same lock as [settle], so the two never interleave.
     */
    fun owe(version: String) = synchronized(GATE_LOCK) {
        prefs.edit { putString(GATE_VERSION, version) }
    }

    /**
     * [version]'s gate has closed (passed, failed or rolled back): forgets it, but only while it
     * is still the one owed. A newer version's gate recorded meanwhile is the newer version's to
     * close, and a check and a clear made apart would wipe it between them.
     */
    fun settle(version: String) = synchronized(GATE_LOCK) {
        if (prefs.getString(GATE_VERSION, null) == version) prefs.edit { remove(GATE_VERSION) }
    }

    private companion object {
        /** Process-wide: the installer's thread and the supervisor's each hold their own ServerPrefs. */
        val GATE_LOCK = Any()

        const val FILE = "server"
        const val RUNNING = "running"
        const val AUTOSTART = "autostart"
        const val GATE_VERSION = "gate_version"
    }
}
