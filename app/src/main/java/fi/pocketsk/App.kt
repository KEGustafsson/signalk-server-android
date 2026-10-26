package fi.pocketsk

import android.app.Application
import fi.pocketsk.server.ServerState
import fi.pocketsk.server.ServerStatus
import fi.pocketsk.widget.BoatWidget

/**
 * The process. It keeps the boat widget in step with the server's state for as long as it lives:
 * a server that starts, stops or gives up is shown on the home screen whether or not a screen of
 * the app is open ([BoatWidget]).
 */
class App : Application() {
    /** The state the widget was last drawn for, so a log line, which observers also hear of, costs nothing. */
    private var drawn: ServerState? = null

    override fun onCreate() {
        super.onCreate()
        ServerStatus.observe { state ->
            if (state != drawn) {
                drawn = state
                BoatWidget.render(this)
            }
        }
    }
}
