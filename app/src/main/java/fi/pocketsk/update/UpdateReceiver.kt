package fi.pocketsk.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import fi.pocketsk.LegacyPlatform

/**
 * Android's installer reporting on the session [AppUpdater] committed. Not exported: only the
 * PendingIntent the app made reaches it.
 *
 * When the installer needs the owner to confirm, it sends the confirmation screen here, and the
 * Dock shows it ([AppUpdater.confirmWith]): Android lets an app start an activity only while it
 * is in front, and the owner may have left the Dock while the APK downloaded, so the screen waits
 * for it. A success is rarely heard, since the install replaces this process; the new one starts
 * the server again from [fi.pocketsk.server.BootReceiver]. Anything else goes back to the Dock.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmation(intent)
                if (confirm == null) AppUpdater.installEnded(PackageInstaller.STATUS_FAILURE, message ?: "the installer gave no confirmation to show")
                else AppUpdater.confirmWith(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> AppUpdater.installEnded(status, message)
        }
    }

    /** The installer's confirmation screen, which it puts in the status intent. */
    private fun confirmation(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else LegacyPlatform.intentExtra(intent, Intent.EXTRA_INTENT)

    companion object {
        /** The action of the status intent [AppUpdater] gives the installer. */
        const val ACTION = "fi.pocketsk.update.INSTALL_STATUS"
    }
}
