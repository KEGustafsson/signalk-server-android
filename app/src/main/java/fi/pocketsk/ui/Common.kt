package fi.pocketsk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.pocketsk.R
import fi.pocketsk.install.InstallManager
import fi.pocketsk.server.ServerState

/** One card of the Dock, or one page block of the wizard, with its title. */
@Composable
internal fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** Lines of output, small and monospaced, as npm and the server print them. */
@Composable
internal fun LogText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
}

/** The server's state in a line, as the Status card and the wizard's last page say it. */
@Composable
internal fun serverStateText(state: ServerState, url: String): String = when (state) {
    ServerState.Stopped -> stringResource(R.string.dock_stopped)
    is ServerState.Starting -> stringResource(R.string.dock_starting, state.version, state.slot)
    is ServerState.Running -> when {
        !state.answering -> stringResource(R.string.server_not_answering, url)
        state.onTrial -> stringResource(R.string.dock_running_on_trial, state.version, state.slot)
        else -> stringResource(R.string.dock_running, state.version, state.slot)
    }
    is ServerState.Waiting -> stringResource(R.string.server_waiting, state.reason, state.delayMs / 1000)
    ServerState.Stopping -> stringResource(R.string.server_stopping)
    is ServerState.Failed -> stringResource(R.string.dock_failed, state.reason)
}

/** An install's progress in a line. */
@Composable
internal fun installStateText(state: InstallManager.State?): String = when (state) {
    null -> stringResource(R.string.setup_install_preparing)
    is InstallManager.State.Installing -> stringResource(R.string.setup_install_npm, state.slot)
    is InstallManager.State.Verifying -> stringResource(R.string.setup_install_verifying, state.slot, InstallManager.SMOKE_PORT)
    is InstallManager.State.Done -> stringResource(R.string.setup_install_done, state.version, state.slot)
    is InstallManager.State.Failed -> stringResource(R.string.setup_install_failed, state.reason)
}

/** The last lines of an install's log, when it has any. */
internal fun installTail(state: InstallManager.State?): String = when (state) {
    is InstallManager.State.Installing -> state.tail
    is InstallManager.State.Verifying -> state.tail
    is InstallManager.State.Failed -> state.tail
    else -> ""
}

/**
 * What the server needs from the phone, each with its state and a way to grant it: shared by the
 * wizard's permission page and the Dock's Settings card (docs/PLAN.md §5.3 step 4). Location
 * (for the phone's GNSS as a Signal K source) is asked for with the bridge, not here.
 */
@Composable
internal fun PermissionRows(permissions: PermissionsView, actions: AppActions) {
    val missing = permissions.notifications == false || permissions.localNetwork == false
    permissions.notifications?.let { PermissionRow(stringResource(R.string.perm_notifications), it) }
    permissions.localNetwork?.let { PermissionRow(stringResource(R.string.perm_local_network), it) }
    if (missing) {
        OutlinedButton(onClick = actions::askPermissions) { Text(stringResource(R.string.perm_allow)) }
    }
    PermissionRow(stringResource(R.string.perm_battery), permissions.batteryUnrestricted)
    if (!permissions.batteryUnrestricted) {
        Text(stringResource(R.string.perm_battery_why), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = actions::openBatterySettings) { Text(stringResource(R.string.perm_battery_open)) }
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.perm_autostart), modifier = Modifier.weight(1f))
        Switch(checked = permissions.autostart, onCheckedChange = actions::setAutostart)
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Text(
            stringResource(if (granted) R.string.perm_granted else R.string.perm_not_granted),
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}
