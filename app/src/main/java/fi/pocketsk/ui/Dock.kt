package fi.pocketsk.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import fi.pocketsk.BuildConfig
import fi.pocketsk.R
import fi.pocketsk.ServerActions
import fi.pocketsk.bridge.BridgeProtocol
import fi.pocketsk.install.InstallManager
import fi.pocketsk.install.ReleaseNotes
import fi.pocketsk.install.ServerVersion
import fi.pocketsk.install.UpdateOffer
import fi.pocketsk.night.NightSettings
import fi.pocketsk.server.Backup
import fi.pocketsk.server.BackupJob
import fi.pocketsk.server.DataVolumes
import fi.pocketsk.server.LogFilter
import fi.pocketsk.server.ServerState
import fi.pocketsk.server.ServerUsage
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Dock's first screen (docs/PLAN.md §8.1): the server's state and its address, updates and
 * rollback, the webapps installed, the helm screen (night filter, dimming, the screen kept on), the
 * boat's alarms while there are any, what the phone grants it, the phone's sensors, its serial
 * devices, where the data is and its log with the diagnostics bundle. The Power and Backup cards
 * come with their own steps.
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
        WebappsCard(model, actions)
        HelmCard(model, actions)
        UpdatesCard(model, actions)
        Section(stringResource(R.string.dock_settings_title)) { PermissionRows(model.permissions, actions) }
        SensorsCard(model, actions)
        DevicesCard(model, actions)
        StorageCard(model, actions)
        BackupCard(model, actions)
        LogsCard(model, actions)
        Text(
            stringResource(R.string.dock_app_version, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The webapps installed (docs/PLAN.md §8.3): each opens in the browser, and with a launcher that
 * takes them each can be pinned to the home screen, where one tap starts a stopped server and
 * opens the webapp once it answers ([fi.pocketsk.WebappShortcuts]).
 */
@Composable
private fun WebappsCard(model: AppModel, actions: AppActions) {
    val webapps = model.webapps ?: return
    Section(stringResource(R.string.dock_webapps_title)) {
        if (webapps.isEmpty()) {
            Text(stringResource(R.string.dock_webapps_none), style = MaterialTheme.typography.bodySmall)
            return@Section
        }
        Text(
            stringResource(if (model.canPin) R.string.dock_webapps_why_pin else R.string.dock_webapps_why),
            style = MaterialTheme.typography.bodySmall,
        )
        for (w in webapps) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.title, modifier = Modifier.weight(1f))
                TextButton(onClick = { actions.openWebapp(w.name) }) { Text(stringResource(R.string.dock_webapps_open)) }
                if (model.canPin) {
                    TextButton(onClick = { actions.pinWebapp(w.name) }) { Text(stringResource(R.string.dock_webapps_pin)) }
                }
            }
        }
    }
}

/**
 * The server's log (docs/PLAN.md §8.1, Logs): its newest lines as they come, narrowed to the
 * problems or to the app's own lines and to the words typed ([LogFilter]), and the diagnostics
 * bundle, shared through the share sheet ([fi.pocketsk.server.DiagnosticsBundle]) after saying
 * what is in it and what is not.
 */
@Composable
private fun LogsCard(model: AppModel, actions: AppActions) {
    Section(stringResource(R.string.dock_log_title)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (kind in LogFilter.Kind.entries) {
                FilterChip(
                    selected = model.logKind == kind,
                    onClick = { actions.filterLog(kind, model.logQuery) },
                    label = { Text(stringResource(logKindLabel(kind))) },
                )
            }
        }
        OutlinedTextField(
            value = model.logQuery,
            onValueChange = { actions.filterLog(model.logKind, it) },
            label = { Text(stringResource(R.string.dock_log_filter)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val filtered = model.logKind != LogFilter.Kind.ALL || model.logQuery.isNotBlank()
        LogText(model.log.ifEmpty { stringResource(if (filtered) R.string.dock_log_none_match else R.string.dock_log_empty) })
        Text(stringResource(R.string.dock_log_share_why), style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions::shareDiagnostics, enabled = !model.sharing) {
                Text(stringResource(R.string.dock_log_share))
            }
            if (model.sharing) CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
    }
}

private fun logKindLabel(kind: LogFilter.Kind): Int = when (kind) {
    LogFilter.Kind.ALL -> R.string.dock_log_all
    LogFilter.Kind.PROBLEMS -> R.string.dock_log_problems
    LogFilter.Kind.APP -> R.string.dock_log_app
}

/**
 * The helm screen (docs/PLAN.md §8.3): the red night filter and its strength, the screen dimmed
 * below the phone's own lowest, and the screen kept on, over every app ([fi.pocketsk.night.NightFilter]).
 * Each change shows at once, so the owner sets them looking at the screen; the Quick Settings tile
 * turns the filter on and off at the helm. They need the app allowed to draw over other apps,
 * which the card asks for first.
 */
@Composable
private fun HelmCard(model: AppModel, actions: AppActions) {
    val night = model.night
    Section(stringResource(R.string.dock_helm_title)) {
        Text(stringResource(R.string.dock_helm_body), style = MaterialTheme.typography.bodySmall)
        if (!model.nightAllowed) {
            Text(stringResource(R.string.dock_helm_overlay_why), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = actions::openOverlaySettings) { Text(stringResource(R.string.dock_helm_overlay_allow)) }
            return@Section
        }
        HelmSwitch(stringResource(R.string.dock_helm_filter), night.filter) { actions.setNight(night.copy(filter = it)) }
        if (night.filter) {
            HelmSlider(stringResource(R.string.dock_helm_strength, night.strength), night.strength, 0) {
                actions.setNight(night.copy(strength = it))
            }
        }
        HelmSwitch(stringResource(R.string.dock_helm_dim), night.dim) { actions.setNight(night.copy(dim = it)) }
        if (night.dim) {
            HelmSlider(stringResource(R.string.dock_helm_brightness, night.brightness), night.brightness, 1) {
                actions.setNight(night.copy(brightness = it))
            }
        }
        HelmSwitch(stringResource(R.string.dock_helm_keep_on), night.keepOn) { actions.setNight(night.copy(keepOn = it)) }
    }
}

/** The whole row is the switch, so a screen reader reads the label with the state and a tap on the words works. */
@Composable
private fun HelmSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A percentage from [min] to 100, in whole steps; the slider carries its label for a screen reader. */
@Composable
private fun HelmSlider(label: String, value: Int, min: Int, onChange: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.clearAndSetSemantics {})
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.roundToInt()) },
        valueRange = min.toFloat()..100f,
        modifier = Modifier.semantics { contentDescription = label },
    )
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
        Text(stringResource(R.string.dock_storage_usb), style = MaterialTheme.typography.bodySmall)
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

/**
 * The Backup card (docs/PLAN.md §8.1, §16 10.8): the server's settings saved as an AES zip where the
 * owner picks, and restored from one ([fi.pocketsk.server.BackupJob]). The passphrase is typed
 * each time and kept nowhere but these fields; a new backup asks for it twice, since a mistyped
 * one would lock the owner out of it for good. A restore asks first, and only with the server
 * stopped.
 */
@Composable
private fun BackupCard(model: AppModel, actions: AppActions) {
    var passphrase by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }
    val job = model.backup
    val working = job is BackupJob.State.Working
    val stopped = model.server is ServerState.Stopped || model.server is ServerState.Failed
    val ok = Backup.passphraseOk(passphrase.toCharArray())
    Section(stringResource(R.string.dock_backup_title)) {
        Text(stringResource(R.string.dock_backup_body), style = MaterialTheme.typography.bodySmall)
        PassphraseField(passphrase, stringResource(R.string.dock_backup_passphrase)) { passphrase = it }
        Text(stringResource(R.string.dock_backup_rule), style = MaterialTheme.typography.bodySmall)
        PassphraseField(again, stringResource(R.string.dock_backup_again)) { again = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { actions.saveBackup(passphrase.toCharArray()) }, enabled = ok && again == passphrase && !working) {
                Text(stringResource(R.string.dock_backup_save))
            }
            OutlinedButton(onClick = { asking = true }, enabled = ok && stopped && !working) { Text(stringResource(R.string.dock_backup_restore)) }
        }
        if (!stopped) Text(stringResource(R.string.dock_backup_stop_first), style = MaterialTheme.typography.bodySmall)
        job?.let { BackupOutcome(it) }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.dock_backup_confirm_title)) },
            text = { Text(stringResource(R.string.dock_backup_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    actions.restoreBackup(passphrase.toCharArray())
                }) { Text(stringResource(R.string.dock_backup_confirm_go)) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.dock_backup_cancel)) } },
        )
    }
}

@Composable
private fun PassphraseField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** What the last backup or restore came to, or how far the one running is. */
@Composable
private fun BackupOutcome(state: BackupJob.State) {
    val text = when (state) {
        is BackupJob.State.Working -> stringResource(
            when {
                !state.restore -> R.string.dock_backup_saving
                state.installing -> R.string.dock_backup_installing
                else -> R.string.dock_backup_restoring
            },
        )
        is BackupJob.State.Saved -> pluralStringResource(R.plurals.dock_backup_saved, state.files, state.files)
        is BackupJob.State.Restored ->
            if (state.npmCode == 0) {
                pluralStringResource(R.plurals.dock_backup_restored, state.files, state.files)
            } else {
                pluralStringResource(R.plurals.dock_backup_restored_no_plugins, state.files, state.files)
            }
        is BackupJob.State.Failed -> when (state.why) {
            BackupJob.Why.WRONG_PASSPHRASE -> stringResource(R.string.dock_backup_wrong)
            BackupJob.Why.NOT_A_BACKUP -> stringResource(R.string.dock_backup_not_ours, state.detail)
            BackupJob.Why.SERVER_RUNNING -> stringResource(R.string.dock_backup_stop_first)
            BackupJob.Why.NO_SERVER_YET -> stringResource(R.string.dock_backup_no_server)
            BackupJob.Why.FAILED -> stringResource(if (state.restore) R.string.dock_backup_restore_failed else R.string.dock_backup_save_failed, state.detail)
        }
    }
    val failed = state is BackupJob.State.Failed || (state is BackupJob.State.Restored && state.npmCode != 0)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state is BackupJob.State.Working) CircularProgressIndicator(modifier = Modifier.size(24.dp))
        Text(text, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
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
        val offer = (model.available as? Available.Loaded)?.let { UpdateOffer.pick(it.versions, slots.liveVersion, slots.history) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (model.available) {
                is Available.Loaded -> offer?.let { v ->
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
        offer?.let { WhatsNew(model.notes, slots.liveVersion, it, actions) }
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

/**
 * "What's new in 2.14.0": signalk-server's release notes from the live version up to the one on
 * offer, read from GitHub the first time they are opened, each release cut to its first lines.
 */
@Composable
private fun WhatsNew(notes: Notes, live: String?, offer: ServerVersion, actions: AppActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    // Open with nothing read: on the first open, after "Check for updates" dropped them, after a
    // rotation; a failure is tried again when the notes are opened again, not in a loop.
    val unknown = notes == Notes.Unknown
    LaunchedEffect(open, unknown) {
        if (open && (unknown || notes is Notes.Failed)) actions.loadNotes()
    }
    TextButton(onClick = { open = !open }) {
        Text(if (open) stringResource(R.string.dock_notes_hide) else stringResource(R.string.dock_notes_show, offer.text))
    }
    if (!open) return
    when (notes) {
        Notes.Unknown, Notes.Loading -> CircularProgressIndicator()
        is Notes.Failed -> Text(stringResource(R.string.dock_notes_failed, notes.reason), color = MaterialTheme.colorScheme.error)
        is Notes.Loaded -> {
            val releases = ReleaseNotes.between(notes.releases, live, offer)
            if (releases.isEmpty()) Text(stringResource(R.string.dock_notes_none))
            val format = DateFormat.getDateInstance(DateFormat.MEDIUM)
            for (r in releases.take(NOTE_RELEASES)) {
                Text(
                    r.published?.let { stringResource(R.string.dock_notes_release, r.version.text, format.format(Date(it))) } ?: r.version.text,
                    style = MaterialTheme.typography.labelLarge,
                )
                if (r.lines.isEmpty()) Text(stringResource(R.string.dock_notes_empty), style = MaterialTheme.typography.bodySmall)
                else Text(r.lines.take(NOTE_LINES).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                val more = r.lines.size - NOTE_LINES
                if (more > 0) Text(pluralStringResource(R.plurals.dock_notes_more, more, more), style = MaterialTheme.typography.bodySmall)
            }
            val older = releases.size - NOTE_RELEASES
            if (older > 0) Text(pluralStringResource(R.plurals.dock_notes_older, older, older), style = MaterialTheme.typography.bodySmall)
        }
    }
    TextButton(onClick = actions::openReleases) { Text(stringResource(R.string.dock_notes_github)) }
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

/** The releases the notes show at most, newest first; the rest are a line and a link to GitHub. */
private const val NOTE_RELEASES = 10

/** A release's lines shown: the head of a long list of changes, the rest counted. */
private const val NOTE_LINES = 12
