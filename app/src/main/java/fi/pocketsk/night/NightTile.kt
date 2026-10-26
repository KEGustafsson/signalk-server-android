package fi.pocketsk.night

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import fi.pocketsk.LegacyPlatform
import fi.pocketsk.MainActivity

/**
 * The night filter's Quick Settings tile (docs/PLAN.md §8.3): one tap at the helm turns the red
 * filter on or off, over whatever webapp is in front, without opening the app. Its strength, the
 * dimming and the screen kept on are the Dock's Helm screen card. With the app not yet allowed to
 * draw over other apps, a tap opens the Dock, where the card asks for it.
 */
class NightTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val night = NightFilter.of(this)
        if (!night.allowed()) {
            openDock()
            return
        }
        val settings = night.settings()
        night.save(settings.copy(filter = !settings.filter))
        refresh()
    }

    private fun openDock() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            LegacyPlatform.startActivityAndCollapse(this, intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val night = NightFilter.of(this)
        tile.state = if (night.allowed() && night.settings().filter) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
