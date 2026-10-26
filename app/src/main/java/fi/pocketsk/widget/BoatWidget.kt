package fi.pocketsk.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import fi.pocketsk.MainActivity
import fi.pocketsk.R
import fi.pocketsk.bridge.BridgeProtocol
import fi.pocketsk.server.ServerState
import fi.pocketsk.server.ServerStatus

/**
 * The home-screen widget (docs/PLAN.md §8.3): the boat's depth, speed over ground and apparent
 * wind, two cells by one, from the server's own data. The bridge's plugin sends them every 2 s
 * while [GlanceWatch] asks it to (a widget placed, the screen on), and each one is drawn as it
 * comes ([render]); a reading gone stale shows as a dash ([GlanceView]). While the server is not
 * running the widget says so in their place. A tap opens the Dock.
 *
 * The system's own update timer is off (`updatePeriodMillis` 0): there is nothing to fetch when
 * the app's process is not running, and the readings come far faster than it allows.
 */
class BoatWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        render(context)
    }

    override fun onEnabled(context: Context) {
        GlanceWatch.widgetsChanged()
    }

    override fun onDisabled(context: Context) {
        GlanceWatch.widgetsChanged()
    }

    companion object {
        private fun component(context: Context) = ComponentName(context, BoatWidget::class.java)

        /** Whether a boat widget is on a home screen. */
        fun placed(context: Context): Boolean =
            AppWidgetManager.getInstance(context).getAppWidgetIds(component(context)).isNotEmpty()

        /**
         * Draws every boat widget from the server's state and its latest glance. Any thread: the
         * update is a call to the system, which redraws the launcher's copy.
         */
        fun render(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(component(context))
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, views(context, ServerStatus.state, ServerStatus.glance, System.currentTimeMillis()))
        }

        private fun views(context: Context, state: ServerState, glance: BridgeProtocol.Glance?, now: Long): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_boat)
            val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            if (state !is ServerState.Running) {
                views.setViewVisibility(R.id.widget_values, View.GONE)
                views.setViewVisibility(R.id.widget_status, View.VISIBLE)
                views.setTextViewText(R.id.widget_status, context.getString(R.string.widget_server, context.getString(stateText(state))))
                return views
            }
            views.setViewVisibility(R.id.widget_values, View.VISIBLE)
            views.setViewVisibility(R.id.widget_status, View.GONE)
            val view = glance?.let { GlanceView.of(it, now) } ?: GlanceView.EMPTY
            val dash = context.getString(R.string.widget_no_value)
            views.setTextViewText(R.id.widget_depth, view.depth?.let { context.getString(R.string.widget_depth_value, it) } ?: dash)
            views.setTextViewText(R.id.widget_sog, view.sog?.let { context.getString(R.string.widget_speed_value, it) } ?: dash)
            views.setTextViewText(R.id.widget_wind, view.windSpeed?.let { context.getString(R.string.widget_speed_value, it) } ?: dash)
            val side = when (view.windSide) {
                GlanceView.Side.PORT -> context.getString(R.string.widget_port)
                GlanceView.Side.STARBOARD -> context.getString(R.string.widget_starboard)
                null -> ""
            }
            views.setTextViewText(
                R.id.widget_wind_label,
                view.windAngle?.let { context.getString(R.string.widget_wind_angle, it, side) } ?: context.getString(R.string.widget_wind),
            )
            return views
        }

        /** The server's state in a word or two, as the Quick Settings tile says it. */
        private fun stateText(state: ServerState): Int = when (state) {
            is ServerState.Running -> R.string.tile_running
            is ServerState.Starting, is ServerState.Waiting -> R.string.tile_starting
            is ServerState.Storage -> if (state.waiting) R.string.tile_storage_waiting else R.string.tile_starting
            ServerState.Stopping -> R.string.server_stopping
            ServerState.Stopped -> R.string.tile_stopped
            is ServerState.Failed -> R.string.tile_failed
        }
    }
}
