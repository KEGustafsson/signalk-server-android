package fi.pocketsk.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import fi.pocketsk.bridge.BridgeProtocol

/**
 * Whether the boat widget wants the server's readings, told to the bridge's plugin as `glance`
 * on or off ([send]): on while a widget is placed and the screen is on, off otherwise. Android
 * does not tell an app whether its widget is in sight, so a screen that is on stands for it; a
 * phone with its screen off asks the server for nothing.
 *
 * Owned by the bridge, started and stopped with it. The widget's own receiver says when the first
 * widget is placed or the last removed ([widgetsChanged]), the screen's broadcasts the rest.
 */
internal class GlanceWatch(context: Context, private val send: (String) -> Unit) {
    private val app = context.applicationContext
    private val power = app.getSystemService(PowerManager::class.java)

    /** What the plugin was last told, null before anything on this link: off is what a new link starts with. */
    private var told: Boolean? = null

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // A screen that comes on shows whatever the widget last drew: stale readings go first.
            if (intent.action == Intent.ACTION_SCREEN_ON) BoatWidget.render(app)
            update()
        }
    }

    private val changed: () -> Unit = ::update

    @Synchronized
    fun start() {
        listener = changed
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(app, screen, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    @Synchronized
    fun stop() {
        if (listener === changed) listener = null
        try {
            app.unregisterReceiver(screen)
        } catch (_: IllegalArgumentException) {
            // Never registered: a stop with no start before it.
        }
        told = null
    }

    /** A new link to the plugin: it starts with the glance off, and is told again. */
    @Synchronized
    fun connected() {
        told = false
        update()
    }

    /** The plugin went: nothing to tell until the next one. */
    @Synchronized
    fun disconnected() {
        told = null
    }

    /** Tells the plugin when what the widget wants has changed. */
    @Synchronized
    fun update() {
        if (told == null) return
        val want = power?.isInteractive != false && BoatWidget.placed(app)
        if (want == told) return
        told = want
        send(BridgeProtocol.glance(want))
    }

    companion object {
        /** The running bridge's [update], for the widget's receiver; null while no server runs. */
        @Volatile private var listener: (() -> Unit)? = null

        /** The first boat widget was placed, or the last removed. */
        fun widgetsChanged() {
            listener?.invoke()
        }
    }
}
