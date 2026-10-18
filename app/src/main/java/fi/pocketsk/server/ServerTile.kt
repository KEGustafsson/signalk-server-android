package fi.pocketsk.server

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import fi.pocketsk.LegacyPlatform
import fi.pocketsk.MainActivity
import fi.pocketsk.R

/**
 * The Quick Settings tile (docs/PLAN.md §6): the server's state, and one tap to start or stop it.
 *
 * A stop is a command to the running service, which a tile may always send. A start is a
 * foreground service start, which Android 12 and later refuse from the background unless an
 * exemption applies; when it is refused, the tile opens the Dock with [MainActivity.ACTION_START]
 * and the Dock starts it, a screen in front being the case every Android allows.
 *
 * The tile is not an active one: the system binds it whenever the panel opens and keeps it
 * listening until the panel closes, so it reads [ServerStatus] on opening and observes it while
 * shown, and a start going on to running moves the tile with it. An active tile would instead be
 * asked to look only when the app requested it, and would show a server that died with the
 * process as still running.
 */
class ServerTile : TileService() {

    /** What the tile shows now, so a log line (which also reaches observers) costs no update. */
    private var shown: ServerState? = null

    private val observer = ServerStatus.Observer { state -> if (state != shown) refresh() }

    override fun onStartListening() {
        super.onStartListening()
        shown = null
        ServerStatus.observe(observer)
    }

    override fun onStopListening() {
        ServerStatus.detach(observer)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (ServerStatus.state.isActive) {
            SignalKService.stop(this)
        } else {
            try {
                SignalKService.start(this)
            } catch (_: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException: the Dock starts it instead.
                openToStart()
            }
        }
        refresh()
    }

    private fun openToStart() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_START)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            LegacyPlatform.startActivityAndCollapse(this, intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val state = ServerStatus.state
        shown = state
        tile.state = if (state.isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = getString(
            when (state) {
                is ServerState.Running -> if (state.answering) R.string.tile_running else R.string.tile_not_answering
                is ServerState.Starting, is ServerState.Waiting -> R.string.tile_starting
                ServerState.Stopping -> R.string.server_stopping
                ServerState.Stopped -> R.string.tile_stopped
                is ServerState.Failed -> R.string.tile_failed
            },
        )
        tile.updateTile()
    }
}
