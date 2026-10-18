package fi.pocketsk.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the server back without anyone opening the app (docs/PLAN.md §6): after a reboot when
 * autostart is on, and after an update of this app when it was running before (an update ends
 * the process, and a boat should not lose its server to one). Both broadcasts are among the
 * few that Android 12 and later still let start a foreground service from the background.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = ServerPrefs(context)
        val start = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> prefs.autostart
            Intent.ACTION_MY_PACKAGE_REPLACED -> prefs.running
            else -> false
        }
        if (!start) return
        try {
            SignalKService.start(context)
        } catch (e: IllegalStateException) {
            // Refused after all (a vendor's own background rules): said in the log, for the screen.
            ServerStatus.log(context).note("could not start after ${intent.action}: $e")
        }
    }
}
