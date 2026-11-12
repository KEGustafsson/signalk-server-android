package fi.pocketsk.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fi.pocketsk.R
import fi.pocketsk.bridge.BridgeProtocol
import fi.pocketsk.satellite.Satellite
import fi.pocketsk.satellite.SatelliteJob
import fi.pocketsk.server.ServerState
import fi.pocketsk.signalk.SignalKUrl

/**
 * The phone's own sensors (docs/PLAN.md §8.1): which ones the server reports is the bridge
 * plugin's settings, in the admin UI; what only the phone can do is here: Level now, the
 * attitude's mounting calibration, taken with the boat lying level and the phone in its holder,
 * and the start again that gives a server started at boot the phone's GPS.
 */
@Composable
internal fun SensorsPage(model: AppModel, actions: AppActions, onBack: () -> Unit) {
    PageHeader(stringResource(R.string.sensors_title), onBack)
    if (model.permissions.location && model.locationHeld == false && model.server.isActive) {
        Block {
            Problem(stringResource(R.string.dock_gps_restart_why))
            Button(onClick = actions::restartWithLocation) { Text(stringResource(R.string.dock_gps_restart)) }
        }
    }
    if (!model.permissions.location) {
        Block {
            Text(stringResource(R.string.perm_location), style = MaterialTheme.typography.titleSmall)
            Note(stringResource(R.string.perm_location_why))
            OutlinedButton(onClick = actions::askLocation) { Text(stringResource(R.string.perm_location_allow)) }
        }
    }
    Section(stringResource(R.string.level_title)) {
        Note(stringResource(R.string.dock_level_why))
        Button(onClick = actions::levelAttitude, enabled = model.server is ServerState.Running) { Text(stringResource(R.string.dock_level)) }
        if (model.server !is ServerState.Running) Note(stringResource(R.string.level_needs_server))
    }
    Explain(stringResource(R.string.dock_sensors_body))
}

/**
 * USB serial devices plugged in (docs/PLAN.md §8.1, Devices), each with how it stands and the
 * bytes read, and Allow for one waiting for the owner's permission. The baud rates are the bridge
 * plugin's settings, since the plugin is what reads the devices' sentences.
 */
@Composable
internal fun UsbPage(model: AppModel, actions: AppActions, onBack: () -> Unit) {
    PageHeader(stringResource(R.string.usb_title), onBack)
    val usb = model.devices.filter { it.kind == BridgeProtocol.USB }
    Block {
        when {
            !model.server.isActive -> Note(stringResource(R.string.dock_devices_stopped))
            usb.isEmpty() -> Note(stringResource(R.string.dock_devices_none))
        }
        if (model.server.isActive) {
            for (d in usb) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(deviceText(d), modifier = Modifier.weight(1f))
                    if (d.state == BridgeProtocol.Device.NEEDS_PERMISSION) {
                        Button(onClick = { actions.allowDevice(d.address) }) { Text(stringResource(R.string.dock_device_allow)) }
                    }
                }
            }
        }
    }
    Explain(stringResource(R.string.dock_devices_body))
}

/**
 * The paired Bluetooth devices, each with a switch that chooses it, since pairing alone says
 * nothing (headsets and cars are paired too); the permission they need is asked for first.
 */
@Composable
internal fun BluetoothPage(model: AppModel, actions: AppActions, onBack: () -> Unit) {
    PageHeader(stringResource(R.string.bluetooth_title), onBack)
    val bluetooth = model.devices.filter { it.kind == BridgeProtocol.BLUETOOTH }
    Block {
        when {
            !model.permissions.bluetooth -> {
                Note(stringResource(R.string.dock_bluetooth_why))
                Button(onClick = actions::askBluetooth) { Text(stringResource(R.string.dock_bluetooth_allow)) }
            }
            !model.server.isActive -> Note(stringResource(R.string.dock_devices_stopped))
            bluetooth.isEmpty() -> Note(stringResource(R.string.dock_bluetooth_none))
        }
        if (model.permissions.bluetooth && model.server.isActive) {
            for (d in bluetooth) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (d.chosen) deviceText(d) else "${d.name} (${d.id})", modifier = Modifier.weight(1f))
                    Switch(checked = d.chosen, onCheckedChange = { actions.chooseBluetooth(d.address, it) })
                }
            }
        }
    }
    Explain(stringResource(R.string.dock_devices_body))
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

/**
 * Satellite mode (docs/PLAN.md §8.4, §16 13): the boat's main Signal K server, found over mDNS
 * while the Dock is in front or typed, followed by this phone's server once its admin approves the
 * access request ([SatelliteJob]), how the link stands, and what of the phone's own goes to it.
 */
@Composable
internal fun MainServerPage(model: AppModel, actions: AppActions, onBack: () -> Unit) {
    PageHeader(stringResource(R.string.dock_satellite_title), onBack)
    when (val s = model.satellite) {
        is SatelliteJob.State.Following -> {
            Block {
                Text(stringResource(R.string.dock_satellite_following, s.name, SignalKUrl.describe(s.url)))
                Text(
                    when (s.link) {
                        BridgeProtocol.SatelliteLink.CONNECTED -> stringResource(R.string.dock_satellite_connected)
                        BridgeProtocol.SatelliteLink.CONNECTING -> stringResource(R.string.dock_satellite_connecting)
                        BridgeProtocol.SatelliteLink.UNAUTHORIZED -> stringResource(R.string.dock_satellite_unauthorized)
                        BridgeProtocol.SatelliteLink.UNREACHABLE -> stringResource(R.string.dock_satellite_unreachable, s.detail.orEmpty())
                        BridgeProtocol.SatelliteLink.OFF, null -> stringResource(R.string.dock_satellite_waiting)
                    },
                    color = if (s.link == BridgeProtocol.SatelliteLink.UNAUTHORIZED) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
                if (!s.secured) Note(stringResource(R.string.dock_satellite_open))
            }
            Section(stringResource(R.string.main_send_title)) { SatellitePush(s, actions) }
            OutlinedButton(onClick = actions::stopFollowing) { Text(stringResource(R.string.dock_satellite_stop)) }
        }
        is SatelliteJob.State.Asking -> Block {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text(
                    stringResource(if (s.reached) R.string.dock_satellite_asked else R.string.dock_satellite_reaching, s.name),
                    modifier = Modifier.weight(1f),
                )
            }
            s.detail?.let { Note(it) }
            OutlinedButton(onClick = actions::stopFollowing) { Text(stringResource(R.string.dock_satellite_cancel)) }
        }
        SatelliteJob.State.Off, is SatelliteJob.State.Ended -> {
            (s as? SatelliteJob.State.Ended)?.let { Block { Problem(stringResource(endedText(it), it.name)) } }
            Section(stringResource(R.string.main_found_title)) {
                if (model.satelliteFound.isEmpty()) Note(stringResource(R.string.dock_satellite_none))
                for (found in model.satelliteFound) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(found.name)
                            Note(SignalKUrl.describe(found.url))
                        }
                        Button(onClick = { actions.followServer(found.url, found.name) }) { Text(stringResource(R.string.dock_satellite_follow)) }
                    }
                }
                var typed by rememberSaveable { mutableStateOf("") }
                val url = SignalKUrl.normalise(typed)?.takeIf { Satellite.target(it) != null }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        label = { Text(stringResource(R.string.dock_satellite_address)) },
                        isError = typed.isNotBlank() && url == null,
                        supportingText = if (typed.isNotBlank() && url == null) {
                            { Text(stringResource(R.string.dock_satellite_bad_address)) }
                        } else {
                            null
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { url?.let { actions.followServer(it, SignalKUrl.describe(it)) } }, enabled = url != null) {
                        Text(stringResource(R.string.dock_satellite_follow))
                    }
                }
            }
            Explain(stringResource(R.string.dock_satellite_why))
        }
    }
}

private fun endedText(s: SatelliteJob.State.Ended): Int = when {
    s.denied -> R.string.dock_satellite_denied
    s.why == Satellite.Why.NO_DEVICE_REQUESTS -> R.string.dock_satellite_no_requests
    s.why == Satellite.Why.ALREADY_ASKED -> R.string.dock_satellite_already
    s.why == Satellite.Why.EXPIRED -> R.string.dock_satellite_expired
    else -> R.string.dock_satellite_not_signalk
}

/** A request for read/write ended without it: the link reads on, and asking again is the remedy. */
private fun writeEndedText(u: SatelliteJob.Upgrade.Ended): Int = when {
    u.denied -> R.string.dock_satellite_write_denied
    u.why == Satellite.Why.NO_DEVICE_REQUESTS -> R.string.dock_satellite_write_no_requests
    u.why == Satellite.Why.ALREADY_ASKED -> R.string.dock_satellite_write_already
    u.why == Satellite.Why.EXPIRED -> R.string.dock_satellite_write_expired
    else -> R.string.dock_satellite_not_signalk
}

/**
 * The push (docs/PLAN.md §16 13.2): which of the phone's own data goes to the main server, and
 * whether the main server lets this phone write, with the request for read/write when it does not.
 */
@Composable
private fun SatellitePush(s: SatelliteJob.State.Following, actions: AppActions) {
    SwitchRow(stringResource(R.string.dock_satellite_push_sensors), s.push.sensors) { on -> actions.setSatellitePush { it.copy(sensors = on) } }
    SwitchRow(stringResource(R.string.dock_satellite_push_serial), s.push.serial) { on -> actions.setSatellitePush { it.copy(serial = on) } }
    val sending = s.push.sensors || s.push.serial
    when (val u = s.upgrade) {
        is SatelliteJob.Upgrade.Asking -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text(
                    stringResource(if (u.reached) R.string.dock_satellite_write_asked else R.string.dock_satellite_reaching, s.name),
                    modifier = Modifier.weight(1f),
                )
            }
            u.detail?.let { Note(it) }
            TextButton(onClick = actions::cancelWrite) { Text(stringResource(R.string.dock_satellite_cancel)) }
        }
        else -> when (s.write) {
            true -> if (sending) Note(stringResource(R.string.dock_satellite_may_write))
            false -> {
                (u as? SatelliteJob.Upgrade.Ended)?.let { Problem(stringResource(writeEndedText(it), s.name)) }
                Note(stringResource(R.string.dock_satellite_read_only, s.name))
                OutlinedButton(onClick = actions::askWrite) { Text(stringResource(R.string.dock_satellite_ask_write)) }
            }
            null -> if (sending) Note(stringResource(R.string.dock_satellite_write_unknown, s.name))
        }
    }
}
