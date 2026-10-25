package fi.pocketsk

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import fi.pocketsk.install.InstallJob
import fi.pocketsk.install.InstallManager
import fi.pocketsk.node.NodeRuntime
import fi.pocketsk.server.ServerEndpoint
import fi.pocketsk.server.ServerState
import fi.pocketsk.server.ServerStatus
import fi.pocketsk.server.SignalKService
import fi.pocketsk.signalk.Webapps

/** What the Dock, the setup wizard and the runtime checks do to the server, written once. */
internal object ServerActions {

    /**
     * What the server needs asked for before it starts, and is not granted yet: notifications
     * (Android 13+), without which it runs unseen, and Android 17's local network access, without
     * which only the phone itself reaches it ([LocalNetwork]). The server starts whatever the
     * answers: without the second, the phone's own browser and the plugins still reach it on the
     * loopback, which for a phone that is the boat's only screen is all there is.
     */
    fun missingPermissions(context: Context): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        if (LocalNetwork.forServer(Build.VERSION.SDK_INT, lanAccess = true) == LocalNetwork.Need.REQUIRED) add(LocalNetwork.PERMISSION)
    }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }

    /** Says in the server's log what was refused, so a tablet that cannot connect has its reason on screen. */
    fun noteRefused(context: Context, granted: Map<String, Boolean>) {
        val log = ServerStatus.log(context)
        if (granted[LocalNetwork.PERMISSION] == false) log.note(context.getString(R.string.server_no_local_network))
        if (granted[Manifest.permission.POST_NOTIFICATIONS] == false) log.note(context.getString(R.string.server_no_notifications))
    }

    /**
     * Installs [source] into the slot that is not live through [InstallJob]; false when one runs
     * already. The new version owes its health gate from just before it goes live, and a running
     * server moves to it once it is (PLAN §5.2 steps 5–6).
     */
    fun install(context: Context, runtime: NodeRuntime, source: InstallManager.Source): Boolean {
        val app = context.applicationContext
        return InstallJob.start(
            InstallManager(app, runtime),
            source,
            onPromoting = { SignalKService.owesGate(app, it) },
            onPromoted = { SignalKService.promoted(app) },
        )
    }

    /** The server on this phone, plain HTTP; with SSL on, the server redirects it to HTTPS itself. */
    fun localUrl(port: Int) = "http://127.0.0.1:$port"

    /** The port of the running server, or Signal K's default when none runs. */
    fun port(): Int = (ServerStatus.state as? ServerState.Running)?.port ?: ServerEndpoint.DEFAULT_PORT

    /**
     * The admin UI in the phone's browser, the way it is used on any Signal K server: the app has
     * no viewer of its own for it (docs/PLAN.md §8.2).
     */
    fun openAdmin(activity: Activity) = browse(activity, "${localUrl(port())}/admin/")

    /** The installed webapp [name] in the phone's browser, where the server serves it (docs/PLAN.md §8.3). */
    fun openWebapp(activity: Activity, name: String) = browse(activity, Webapps.url(localUrl(port()), name))

    private fun browse(activity: Activity, url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, activity.getString(R.string.no_browser, url), Toast.LENGTH_LONG).show()
        }
    }
}
