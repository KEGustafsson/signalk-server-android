package fi.pocketsk

import android.app.Application
import fi.pocketsk.night.NightFilter
import fi.pocketsk.server.ServerState
import fi.pocketsk.server.ServerStatus
import fi.pocketsk.widget.BoatWidget

/**
 * The process. It keeps the boat widget in step with the server's state for as long as it lives:
 * a server that starts, stops or gives up is shown on the home screen whether or not a screen of
 * the app is open ([BoatWidget]). It holds the helm screen's overlay ([night]) and puts it back
 * when the process starts again, as the owner left it.
 */
class App : Application() {
    /** The state the widget was last drawn for, so a log line, which observers also hear of, costs nothing. */
    private var drawn: ServerState? = null

    /** The night filter, the dimmed screen and the screen kept on: one overlay for the process. */
    internal val night by lazy { NightFilter(this) }

    override fun onCreate() {
        super.onCreate()
        ServerStatus.observe { state ->
            if (state != drawn) {
                drawn = state
                BoatWidget.render(this)
            }
        }
        night.apply()
    }
}
