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
    var gateVersion: String?
        get() = prefs.getString(GATE_VERSION, null)
        set(value) = prefs.edit { if (value == null) remove(GATE_VERSION) else putString(GATE_VERSION, value) }

    private companion object {
        const val FILE = "server"
        const val RUNNING = "running"
        const val AUTOSTART = "autostart"
        const val GATE_VERSION = "gate_version"
    }
}
