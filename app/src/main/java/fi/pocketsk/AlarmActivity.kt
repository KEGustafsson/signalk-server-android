package fi.pocketsk

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import fi.pocketsk.server.ServerStatus
import fi.pocketsk.server.SignalKService
import fi.pocketsk.ui.AlarmScreen
import fi.pocketsk.ui.PocketTheme

/**
 * The boat's alarm on the whole screen (docs/PLAN.md §7.1, alarms in), opened by the alarm
 * notification's full-screen intent ([fi.pocketsk.bridge.PhoneAlarm]): over the lock screen and
 * with the screen turned on (both in the manifest, so they hold from the first frame), kept on
 * while it is shown. Each alarm has its Silence (or Acknowledge); the screen closes by itself once
 * no alarm is left, and Close leaves it ringing, for the crew member who would rather look first.
 */
class AlarmActivity : ComponentActivity() {
    private var view by mutableStateOf(ServerStatus.alarms)

    private val observer = ServerStatus.Observer {
        view = ServerStatus.alarms
        if (view.alarms.isEmpty()) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            PocketTheme {
                Scaffold { padding ->
                    AlarmScreen(view, onSilence = ::silence, onClose = ::finish, modifier = Modifier.padding(padding))
                }
            }
        }
        // Tells the observer the alarms at once, which closes a screen opened after they ended.
        ServerStatus.observe(observer)
    }

    override fun onDestroy() {
        ServerStatus.detach(observer)
        super.onDestroy()
    }

    private fun silence(id: String) = SignalKService.silence(this, id)
}
