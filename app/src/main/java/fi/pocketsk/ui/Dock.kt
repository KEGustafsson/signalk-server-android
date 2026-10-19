package fi.pocketsk.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fi.pocketsk.BuildConfig
import fi.pocketsk.R
import fi.pocketsk.ServerActions
import fi.pocketsk.bridge.BridgeProtocol
import fi.pocketsk.install.InstallManager
import fi.pocketsk.install.UpdateOffer
import fi.pocketsk.server.ServerState
import java.text.DateFormat
import java.util.Date

/**
 * The Dock's first screen (docs/PLAN.md §8.1): the server's state and its address, updates and
 * rollback, what the phone grants it, the phone's sensors, its serial devices and the newest of
 * its log. The Power card, the diagnostics bundle and backup come with their own steps.
 */
@Composable
internal fun DockScreen(model: AppModel, actions: AppActions, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = actions::openRuntimeChecks) { Text(stringResource(R.string.dock_runtime_checks)) }
        }
        StatusCard(model, actions)
        UpdatesCard(model, actions)
        Section(stringResource(R.string.dock_settings_title)) { PermissionRows(model.permissions, actions) }
        SensorsCard(model, actions)
        DevicesCard(model, actions)
        Section(stringResource(R.string.dock_log_title)) {
            LogText(model.log.ifEmpty { stringResource(R.string.dock_log_empty) })
        }
        Text(
            stringResource(R.string.dock_app_version, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The phone's sensors: which ones the server reports is the bridge plugin's settings, in the admin
 * UI; what only the phone can do is here: "level now", the attitude's mounting calibration
 * (docs/PLAN.md §8.1), taken with the boat lying level and the phone in its holder.
 */
@Composable
private fun SensorsCard(model: AppModel, actions: AppActions) {
    Section(stringResource(R.string.dock_sensors_title)) {
        Text(stringResource(R.string.dock_sensors_body), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.dock_level_why), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = actions::levelAttitude, enabled = model.server is ServerState.Running) {
            Text(stringResource(R.string.dock_level))
        }
    }
}

/**
 * The serial devices (docs/PLAN.md §8.1, Devices): the USB ones plugged in, each with how it
 * stands, the bytes read and Allow for one waiting for the owner's permission, and the paired
 * Bluetooth ones, each with a switch that chooses it, since pairing alone says nothing (headsets
 * and cars are paired too). The USB baud rates are the bridge plugin's settings, since the plugin
 * is what reads the devices' sentences.
 */
@Composable
private fun DevicesCard(model: AppModel, actions: AppActions) {
    val usb = model.devices.filter { it.kind == BridgeProtocol.USB }
    val bluetooth = model.devices.filter { it.kind == BridgeProtocol.BLUETOOTH }
    Section(stringResource(R.string.dock_devices_title)) {
        Text(stringResource(R.string.dock_devices_body), style = MaterialTheme.typography.bodySmall)
        if (!model.server.isActive) {
            Text(stringResource(R.string.dock_devices_stopped))
            return@Section
        }
        Text(stringResource(R.string.dock_devices_usb), style = MaterialTheme.typography.titleSmall)
        if (usb.isEmpty()) Text(stringResource(R.string.dock_devices_none))
        for (d in usb) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(deviceText(d), modifier = Modifier.weight(1f))
                if (d.state == BridgeProtocol.Device.NEEDS_PERMISSION) {
                    OutlinedButton(onClick = { actions.allowDevice(d.address) }) { Text(stringResource(R.string.dock_device_allow)) }
                }
            }
        }
        Text(stringResource(R.string.dock_devices_bluetooth), style = MaterialTheme.typography.titleSmall)
        when {
            !model.permissions.bluetooth -> {
                Text(stringResource(R.string.dock_bluetooth_why), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = actions::askBluetooth) { Text(stringResource(R.string.dock_bluetooth_allow)) }
            }
            bluetooth.isEmpty() -> Text(stringResource(R.string.dock_bluetooth_none))
        }
        for (d in bluetooth) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (d.chosen) deviceText(d) else "${d.name} (${d.id})", modifier = Modifier.weight(1f))
                Switch(checked = d.chosen, onCheckedChange = { actions.chooseBluetooth(d.address, it) })
            }
        }
    }
}

/** One device's line: its name and id, then how it stands. */
@Composable
private fun deviceText(d: BridgeProtocol.Device): String {
    val label = "${d.name} (${d.id})"
    val read = Formatter.formatShortFileSize(LocalContext.current, d.bytes)
    return when (d.state) {
        BridgeProtocol.Device.OPEN ->
            if (d.kind == BridgeProtocol.BLUETOOTH) stringResource(R.string.dock_device_connected, label, read)
            else stringResource(R.string.dock_device_open, label, d.baud, read)
        BridgeProtocol.Device.CONNECTING ->
            d.detail?.let { stringResource(R.string.dock_device_retrying, label, it) } ?: stringResource(R.string.dock_device_connecting, label)
        BridgeProtocol.Device.NEEDS_PERMISSION -> stringResource(R.string.dock_device_permission, label)
        BridgeProtocol.Device.IDLE -> stringResource(R.string.dock_device_idle, label)
        else -> stringResource(R.string.dock_device_error, label, d.detail.orEmpty())
    }
}

/** Running or not, which version from which slot, where the boat reaches it; Start, Stop, Restart, admin UI. */
@Composable
private fun StatusCard(model: AppModel, actions: AppActions) {
    val state = model.server
    val port = (state as? ServerState.Running)?.port
    Section(stringResource(R.string.dock_status_title)) {
        Text(serverStateText(state, ServerActions.localUrl(port ?: ServerActions.port())))
        if (port != null) {
            model.lanAddress?.let { Text(stringResource(R.string.dock_url_lan, "http://$it:$port")) }
            Text(stringResource(R.string.dock_url_phone, ServerActions.localUrl(port)))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.isActive) {
                OutlinedButton(onClick = actions::stopServer) { Text(stringResource(R.string.server_action_stop)) }
                OutlinedButton(onClick = actions::restartServer) { Text(stringResource(R.string.server_action_restart)) }
            } else {
                Button(onClick = actions::startServer, enabled = model.slots?.liveVersion != null) {
                    Text(stringResource(R.string.dock_start))
                }
            }
            Button(onClick = actions::openAdmin, enabled = state is ServerState.Running && state.answering) {
                Text(stringResource(R.string.dock_open_admin))
            }
        }
    }
}

/**
 * The live slot and the other one, a newer release to install when npm has one, the rollback to
 * the other slot's version, and the newest of the install history with how each went.
 */
@Composable
private fun UpdatesCard(model: AppModel, actions: AppActions) {
    val slots = model.slots
    Section(stringResource(R.string.dock_updates_title)) {
        if (slots == null) {
            CircularProgressIndicator()
            return@Section
        }
        Text(
            if (slots.live != null && slots.liveVersion != null) stringResource(R.string.dock_live, slots.liveVersion, slots.live)
            else stringResource(R.string.dock_nothing_live),
        )
        slots.otherVersion?.let { Text(stringResource(R.string.dock_other, it, slots.other)) }
        if (model.installing || model.install is InstallManager.State.Failed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (model.installing) CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                Text(installStateText(model.install))
            }
            installTail(model.install).takeIf { it.isNotEmpty() }?.let { LogText(it.lines().takeLast(INSTALL_LINES).joinToString("\n")) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val available = model.available) {
                is Available.Loaded -> UpdateOffer.pick(available.versions, slots.liveVersion, slots.history)?.let { v ->
                    Button(onClick = { actions.install(InstallManager.Source.Registry(v.text)) }, enabled = !model.installing) {
                        Text(stringResource(R.string.dock_install, v.text))
                    }
                } ?: Text(stringResource(R.string.dock_up_to_date), modifier = Modifier.align(Alignment.CenterVertically))
                Available.Loading -> CircularProgressIndicator()
                else -> Unit
            }
            OutlinedButton(onClick = { actions.loadVersions(refresh = true) }, enabled = model.available != Available.Loading) {
                Text(stringResource(R.string.dock_check_updates))
            }
            slots.otherVersion?.let { other ->
                OutlinedButton(onClick = actions::rollBack, enabled = !model.installing && !slots.history.isBad(other)) {
                    Text(stringResource(R.string.dock_rollback, other))
                }
            }
        }
        (model.available as? Available.Failed)?.let {
            Text(stringResource(R.string.setup_version_failed, it.reason), color = MaterialTheme.colorScheme.error)
        }
        val recent = slots.history.newestFirst.take(HISTORY_LINES)
        if (recent.isNotEmpty()) {
            Text(stringResource(R.string.dock_history), style = MaterialTheme.typography.labelLarge)
            val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            for (e in recent) {
                Text(
                    stringResource(R.string.dock_history_line, format.format(Date(e.at)), e.outcome.name, e.version, e.slot),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private const val INSTALL_LINES = 15
private const val HISTORY_LINES = 5
