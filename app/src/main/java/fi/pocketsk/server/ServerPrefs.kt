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

    /** The setup wizard has been finished once; the Dock comes first from then on. */
    var setupDone: Boolean
        get() = prefs.getBoolean(SETUP_DONE, false)
        set(value) = prefs.edit { putBoolean(SETUP_DONE, value) }

    /**
     * Precise location has been asked for once. Android shows no rationale both before the first
     * request and after a refusal it will not ask again, so only this tells the two apart.
     */
    var locationAsked: Boolean
        get() = prefs.getBoolean(LOCATION_ASKED, false)
        set(value) = prefs.edit { putBoolean(LOCATION_ASKED, value) }

    /** Bluetooth (Nearby devices) has been asked for once, for the same reason as [locationAsked]. */
    var bluetoothAsked: Boolean
        get() = prefs.getBoolean(BLUETOOTH_ASKED, false)
        set(value) = prefs.edit { putBoolean(BLUETOOTH_ASKED, value) }

    /**
     * Bluetooth LE scanning (BLUETOOTH_SCAN) has been asked for once, apart from [bluetoothAsked]:
     * a phone set up before the app scanned has asked for BLUETOOTH_CONNECT alone, and must get
     * the dialog for this one rather than the settings page.
     */
    var bluetoothScanAsked: Boolean
        get() = prefs.getBoolean(BLUETOOTH_SCAN_ASKED, false)
        set(value) = prefs.edit { putBoolean(BLUETOOTH_SCAN_ASKED, value) }

    /**
     * The card the server's data folders live on ([DataStore], [DataVolumes]), by its UUID; null
     * for the phone, whose shared storage holds them then ([DataVolumes.phone]). [dataVolumeName] is its name as the Dock last showed it, for the messages
     * about a card that is not there.
     */
    var dataVolume: String?
        get() = prefs.getString(DATA_VOLUME, null)
        set(value) = prefs.edit { if (value == null) remove(DATA_VOLUME) else putString(DATA_VOLUME, value) }

    var dataVolumeName: String?
        get() = prefs.getString(DATA_VOLUME_NAME, null)
        set(value) = prefs.edit { if (value == null) remove(DATA_VOLUME_NAME) else putString(DATA_VOLUME_NAME, value) }

    /** The Dock looks for a newer build of the app once a day ([fi.pocketsk.update.AppUpdater]). */
    var appUpdateChecks: Boolean
        get() = prefs.getBoolean(APP_UPDATE_CHECKS, true)
        set(value) = prefs.edit { putBoolean(APP_UPDATE_CHECKS, value) }

    /**
     * Other devices on the boat's network reach the server (docs/PLAN.md §11): on by default, a
     * server is a hub. Off, every node the supervisor starts listens on 127.0.0.1 alone
     * ([fi.pocketsk.node.NodeRuntime.LOOPBACK_ENV]), from the next start.
     */
    var lanAccess: Boolean
        get() = prefs.getBoolean(LAN_ACCESS, true)
        set(value) = prefs.edit { putBoolean(LAN_ACCESS, value) }

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
        const val LAN_ACCESS = "lan_access"
        const val APP_UPDATE_CHECKS = "app_update_checks"
        const val SETUP_DONE = "setup_done"
        const val LOCATION_ASKED = "location_asked"
        const val BLUETOOTH_ASKED = "bluetooth_asked"
        const val BLUETOOTH_SCAN_ASKED = "bluetooth_scan_asked"
        const val GATE_VERSION = "gate_version"
        const val DATA_VOLUME = "data_volume"
        const val DATA_VOLUME_NAME = "data_volume_name"
    }
}
