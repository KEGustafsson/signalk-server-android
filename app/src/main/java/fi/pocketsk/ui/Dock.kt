package fi.pocketsk.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import fi.pocketsk.BuildConfig
import fi.pocketsk.R
import fi.pocketsk.ServerActions
import fi.pocketsk.bridge.BridgeProtocol
import fi.pocketsk.install.InstallManager
import fi.pocketsk.install.UpdateOffer
import fi.pocketsk.server.DataVolumes
import fi.pocketsk.server.ServerState
import fi.pocketsk.server.ServerUsage
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Dock's first screen (docs/PLAN.md §8.1): the server's state and its address, updates and
 * rollback, the boat's alarms while there are any, what the phone grants it, the phone's sensors, its serial devices and the newest of
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
        if (model.alarms.alarms.isNotEmpty()) {
            Section(stringResource(R.string.dock_alarms_title)) { AlarmRows(model.alarms, actions::silenceAlarm) }
        }
        StatusCard(model, actions)
        UpdatesCard(model, actions)
        Section(stringResource(R.string.dock_settings_title)) { PermissionRows(model.permissions, actions) }
        SensorsCard(model, actions)
        DevicesCard(model, actions)
        StorageCard(model, actions)
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
        if (model.permissions.location && model.locationHeld == false && model.server.isActive) {
            Text(stringResource(R.string.dock_gps_restart_why), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = actions::restartWithLocation) { Text(stringResource(R.string.dock_gps_restart)) }
        }
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

/**
 * Where the server keeps its data (docs/PLAN.md §16 9.5.9): the phone or a card, each with its
 * room. A choice is confirmed first, since it restarts the server and may copy gigabytes; a chosen
 * card that is out of the phone stays listed, so the owner can see why the server waits.
 */
@Composable
private fun StorageCard(model: AppModel, actions: AppActions) {
    val view = model.storage ?: return
    var asked by remember { mutableStateOf<Choice?>(null) }
    Section(stringResource(R.string.dock_storage_title)) {
        Text(stringResource(R.string.dock_storage_body), style = MaterialTheme.typography.bodySmall)
        StorageRow(
            stringResource(R.string.dock_storage_phone) + " · " + room(view.phoneFree, view.phoneTotal),
            selected = view.chosen == null,
        ) { if (view.chosen != null) asked = Choice(null) }
        val inPhone = view.volumes.filter { it.mounted }
        for (v in inPhone) {
            StorageRow("${v.name} · ${room(v.free, v.total)}", selected = v.uuid == view.chosen) {
                if (v.uuid != view.chosen) asked = Choice(v)
            }
        }
        if (view.chosen != null && inPhone.none { it.uuid == view.chosen }) {
            StorageRow(stringResource(R.string.dock_storage_missing, view.chosenName ?: view.chosen), selected = true, enabled = false) {}
        }
        if (inPhone.isEmpty()) Text(stringResource(R.string.dock_storage_none), style = MaterialTheme.typography.bodySmall)
        if (view.chosen != null) Text(stringResource(R.string.dock_storage_charts), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.dock_storage_upload), style = MaterialTheme.typography.bodySmall)
        view.inbox?.let { waiting ->
            Text(stringResource(R.string.dock_inbox_where), style = MaterialTheme.typography.bodySmall)
            if (waiting.files > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringResource(R.plurals.dock_inbox_waiting, waiting.files, waiting.files, Formatter.formatShortFileSize(LocalContext.current, waiting.bytes)),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = actions::takeInbox) { Text(stringResource(R.string.dock_inbox_take)) }
                }
            }
        }
    }
    asked?.let { choice ->
        AlertDialog(
            onDismissRequest = { asked = null },
            title = { Text(stringResource(R.string.dock_storage_confirm_title)) },
            text = {
                Text(
                    choice.volume?.let { stringResource(R.string.dock_storage_confirm_card, it.name) }
                        ?: stringResource(R.string.dock_storage_confirm_phone),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    asked = null
                    actions.chooseStorage(choice.volume)
                }) { Text(stringResource(R.string.dock_storage_move)) }
            },
            dismissButton = { TextButton(onClick = { asked = null }) { Text(stringResource(R.string.dock_storage_cancel)) } },
        )
    }
}

/** A volume's free and total room in a line. */
@Composable
private fun room(free: Long, total: Long): String {
    val context = LocalContext.current
    return stringResource(
        R.string.dock_storage_free,
        Formatter.formatShortFileSize(context, free),
        Formatter.formatShortFileSize(context, total),
    )
}

/** A choice waiting for its confirmation: a card, or the phone when [volume] is null. */
private class Choice(val volume: DataVolumes.Volume?)

@Composable
private fun StorageRow(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(text, modifier = Modifier.padding(start = 8.dp))
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

/**
 * Running or not, which version from which slot, for how long and at what cost (CPU, memory),
 * where the boat reaches it; Start, Stop, Restart, admin UI.
 */
@Composable
private fun StatusCard(model: AppModel, actions: AppActions) {
    val state = model.server
    val port = (state as? ServerState.Running)?.port
    Section(stringResource(R.string.dock_status_title)) {
        Text(serverStateText(state, ServerActions.localUrl(port ?: ServerActions.port())))
        if (state is ServerState.Running) {
            model.usage?.let { Text(usageText(it), style = MaterialTheme.typography.bodySmall) }
            model.deltaRate?.let { Text(stringResource(R.string.dock_delta_rate, deltaRateText(it)), style = MaterialTheme.typography.bodySmall) }
        }
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

/** "Up 3:12:05 · CPU 4 % of one core · memory 182 MB", as much of it as is known. */
@Composable
private fun usageText(u: ServerUsage.Usage): String {
    val up = DateUtils.formatElapsedTime(u.up / 1000)
    val cpu = u.cpuPercent?.roundToInt()
    val memory = u.memoryBytes?.let { Formatter.formatShortFileSize(LocalContext.current, it) }
    return when {
        cpu != null && memory != null -> stringResource(R.string.dock_usage, up, cpu, memory)
        memory != null -> stringResource(R.string.dock_usage_memory, up, memory)
        else -> stringResource(R.string.dock_uptime, up)
    }
}

/** A delta rate as a person reads it: a tenth under ten a second ("2.4"), whole above ("118"). */
private fun deltaRateText(rate: Double): String =
    if (rate < 10) String.format(Locale.getDefault(), "%.1f", rate) else rate.roundToInt().toString()

private const val INSTALL_LINES = 15
private const val HISTORY_LINES = 5
