package fi.pocketsk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fi.pocketsk.R
import fi.pocketsk.bridge.AlarmBook
import fi.pocketsk.bridge.BridgeProtocol.Alarm
import fi.pocketsk.bridge.PhoneAlarm

/**
 * The alarm screen: the most urgent alarm large, the others below it, each with its Silence
 * where there is something to do about it, and Close. Also the Dock's alarms card, in [AlarmRows].
 */
@Composable
internal fun AlarmScreen(view: AlarmBook.View, onSilence: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        view.head?.let { head ->
            Text(
                PhoneAlarm.stateLabel(LocalContext.current, head.state),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(head.message.ifEmpty { head.path }, style = MaterialTheme.typography.headlineMedium)
            Text(head.path, style = MaterialTheme.typography.bodyMedium)
        }
        AlarmRows(view, onSilence, large = true)
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.alarm_close)) }
    }
}

/** Every alarm with its Silence, or a word that it is silenced on the phone. */
@Composable
internal fun AlarmRows(view: AlarmBook.View, onSilence: (String) -> Unit, large: Boolean = false) {
    for (a in view.alarms) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!large || a != view.head) Text(alarmLine(a))
            val quiet = a.id in view.quiet
            if (quiet) Text(stringResource(R.string.alarm_quiet_here), style = MaterialTheme.typography.bodySmall)
            if (a.action != null || !quiet) {
                Button(
                    onClick = { onSilence(a.id) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = if (large) Modifier.fillMaxWidth() else Modifier,
                ) { Text(stringResource(PhoneAlarm.actionLabel(a))) }
            }
        }
    }
}

@Composable
private fun alarmLine(a: Alarm): String =
    stringResource(R.string.alarm_line, PhoneAlarm.stateLabel(LocalContext.current, a.state), a.message.ifEmpty { a.path })
