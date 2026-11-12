package fi.pocketsk.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import fi.pocketsk.R
import fi.pocketsk.ServerActions
import fi.pocketsk.install.InstallHistory
import fi.pocketsk.install.InstallManager
import fi.pocketsk.install.ReleaseNotes
import fi.pocketsk.install.ServerVersion
import fi.pocketsk.server.Backup
import fi.pocketsk.server.BackupJob
import fi.pocketsk.server.DataVolumes
import fi.pocketsk.server.LogFilter
import fi.pocketsk.server.ResetJob
import fi.pocketsk.server.ServerState
import java.text.DateFormat
import java.util.Date

/**
 * The server's versions (docs/PLAN.md §8.1, §16 15): the one running and the one kept to go back
 * to, a newer release with what is new in it, the install running with its last lines, the
 * rollback and the reinstall, any other version npm has (betas and builds among them,
 * [VersionChooser]), and the newest of the install history. The slots themselves are the
 * installer's business and are not named: to the owner there is the version running and the one
 * to go back to.
 */
@Composable
internal fun VersionsPage(model: AppModel, actions: AppActions, onBack: () -> Unit) {
    PageHeader(stringResource(R.string.versions_title), onBack)
    val slots = model.slots
    if (slots == null) {
        CircularProgressIndicator()
        return
    }
    val busy = model.installing || model.resetting
    var reinstalling by remember { mutableStateOf(false) }
    Block {
        Text(
            slots.liveVersion?.let { stringResource(R.string.dock_live, it) } ?: stringResource(R.string.dock_nothing_live),
            style = MaterialTheme.typography.titleMedium,
        )
        slots.otherVersion?.let { Note(stringResource(R.string.dock_other, it)) }
        if (model.installing || model.install is InstallManager.State.Failed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (model.installing) CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp).size(24.dp))
                Text(installStateText(model.install), color = if (model.installing) Color.Unspecified else MaterialTheme.colorScheme.error)
            }
            installTail(model.install).takeIf { it.isNotEmpty() }?.let { LogText(it.lines().takeLast(INSTALL_LINES).joinToString("\n")) }
        }
        val offer = serverOffer(model)
        when (model.available) {
            is Available.Loaded -> offer?.let { v ->
                Button(onClick = { actions.install(InstallManager.Source.Registry(v.text)) }, enabled = !busy) {
                    Text(stringResource(R.string.dock_install, v.text))
                }
            } ?: Note(stringResource(R.string.dock_up_to_date))
            Available.Loading -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
            is Available.Failed -> Problem(stringResource(R.string.setup_version_failed, (model.available as Available.Failed).reason))
            Available.Unknown -> Unit
        }
        offer?.let { WhatsNew(model.notes, slots.liveVersion, it, actions) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { actions.loadVersions(refresh = true) }, enabled = model.available != Available.Loading) {
                Text(stringResource(R.string.dock_check_updates))
            }
            slots.otherVersion?.let { other ->
                OutlinedButton(onClick = actions::rollBack, enabled = !busy && !slots.history.isBad(other)) {
                    Text(stringResource(R.string.dock_rollback, other))
                }
            }
            // Only a version npm can name can be fetched again: a build from a file cannot.
            slots.liveVersion?.takeIf { ServerVersion.parse(it) != null }?.let { live ->
                OutlinedButton(onClick = { reinstalling = true }, enabled = !busy) { Text(stringResource(R.string.versions_reinstall, live)) }
            }
        }
    }
    Section(stringResource(R.string.versions_other_title)) {
        Note(stringResource(R.string.versions_other_text))
        var choosing by rememberSaveable { mutableStateOf(false) }
        if (!choosing) {
            OutlinedButton(onClick = { choosing = true }) { Text(stringResource(R.string.versions_open)) }
        } else {
            VersionChooser(model, actions, live = slots.liveVersion, kept = slots.otherVersion, history = slots.history) { source, allowBad ->
                choosing = false
                actions.install(source, allowBad)
            }
            TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.versions_close)) }
        }
    }
    val recent = slots.history.newestFirst.take(HISTORY_LINES)
    if (recent.isNotEmpty()) {
        Section(stringResource(R.string.dock_history)) {
            val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            for (e in recent) {
                Note(stringResource(R.string.dock_history_line, format.format(Date(e.at)), e.version, outcomeText(e.outcome)))
            }
        }
    }
    if (reinstalling && slots.liveVersion != null) {
        val live = slots.liveVersion
        AlertDialog(
            onDismissRequest = { reinstalling = false },
            title = { Text(stringResource(R.string.versions_reinstall_title, live)) },
            text = {
                Text(
                    slots.otherVersion?.takeIf { it != live }?.let { stringResource(R.string.versions_reinstall_replaces, live, it) }
                        ?: stringResource(R.string.versions_reinstall_text, live),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    reinstalling = false
                    actions.install(InstallManager.Source.Registry(live), allowBad = slots.history.isBad(live))
                }) { Text(stringResource(R.string.versions_reinstall, live)) }
            },
            dismissButton = { TextButton(onClick = { reinstalling = false }) { Text(stringResource(R.string.dock_storage_cancel)) } },
        )
    }
}

/** How an install went, in words, for the History list. */
@Composable
private fun outcomeText(outcome: InstallHistory.Outcome): String = stringResource(
    when (outcome) {
        InstallHistory.Outcome.PROMOTED -> R.string.dock_history_installed
        InstallHistory.Outcome.FAILED -> R.string.dock_history_failed
        InstallHistory.Outcome.ROLLED_BACK -> R.string.dock_history_rolled_back
        InstallHistory.Outcome.BAD -> R.string.dock_history_bad
    },
)

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
        Notes.Unknown, Notes.Loading -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
        is Notes.Failed -> Problem(stringResource(R.string.dock_notes_failed, notes.reason))
        is Notes.Loaded -> {
            val releases = ReleaseNotes.between(notes.releases, live, offer)
            if (releases.isEmpty()) Text(stringResource(R.string.dock_notes_none))
            val format = DateFormat.getDateInstance(DateFormat.MEDIUM)
            for (r in releases.take(NOTE_RELEASES)) {
                Text(
                    r.published?.let { stringResource(R.string.dock_notes_release, r.version.text, format.format(Date(it))) } ?: r.version.text,
                    style = MaterialTheme.typography.labelLarge,
                )
                if (r.lines.isEmpty()) Note(stringResource(R.string.dock_notes_empty))
                else Note(r.lines.take(NOTE_LINES).joinToString("\n"))
                val more = r.lines.size - NOTE_LINES
                if (more > 0) Note(pluralStringResource(R.plurals.dock_notes_more, more, more))
            }
            val older = releases.size - NOTE_RELEASES
            if (older > 0) Note(pluralStringResource(R.plurals.dock_notes_older, older, older))
        }
    }
    TextButton(onClick = actions::openReleases) { Text(stringResource(R.string.dock_notes_github)) }
}

/**
 * Who reaches the server (docs/PLAN.md §8.2, §11): the boat's network, tablets and plotters on
 * it, or this phone alone. Two choices rather than a switch, each saying what it means; a running
 * server restarts to take the change.
 */
@Composable
internal fun AccessPage(model: AppModel, actions: AppActions, onBack: () -> Unit) {
    PageHeader(stringResource(R.string.access_title), onBack)
    Block {
        ChoiceRow(stringResource(R.string.access_lan), model.lanAccess, stringResource(R.string.dock_lan_access_on)) {
            if (!model.lanAccess) actions.setLanAccess(true)
        }
        ChoiceRow(stringResource(R.string.access_phone), !model.lanAccess, stringResource(R.string.dock_lan_access_off)) {
            if (model.lanAccess) actions.setLanAccess(false)
        }
        if (model.server.isActive) Note(stringResource(R.string.access_restarts))
    }
    (model.server as? ServerState.Running)?.let { running ->
        Section(stringResource(R.string.access_addresses)) {
            if (running.lan) model.lanAddress?.let { Text(stringResource(R.string.dock_url_lan, "http://$it:${running.port}")) }
            Text(stringResource(R.string.dock_url_phone, ServerActions.localUrl(running.port)))
        }
    }
}

/**
 * Where the server keeps its data (docs/PLAN.md §16 9.5.9): the phone or a card, each with its
 * room. A choice is confirmed first, since it restarts the server and may copy gigabytes; a chosen
 * card that is out of the phone stays listed, so the owner can see why the server waits. Below
 * it, the files a computer copied over USB, waiting to be taken in.
 */
@Composable
internal fun DataPage(model: AppModel, actions: AppActions, onBack: () -> Unit) {
    PageHeader(stringResource(R.string.data_title), onBack)
    val view = model.storage ?: return
    var asked by remember { mutableStateOf<Choice?>(null) }
    Section(stringResource(R.string.data_where)) {
        ChoiceRow(stringResource(R.string.dock_storage_phone), selected = view.chosen == null, detail = room(view.phoneFree, view.phoneTotal)) {
            if (view.chosen != null) asked = Choice(null)
        }
        val inPhone = view.volumes.filter { it.mounted }
        for (v in inPhone) {
            ChoiceRow(v.name, selected = v.uuid == view.chosen, detail = room(v.free, v.total)) {
                if (v.uuid != view.chosen) asked = Choice(v)
            }
        }
        if (view.chosen != null && inPhone.none { it.uuid == view.chosen }) {
            ChoiceRow(stringResource(R.string.dock_storage_missing, view.chosenName ?: view.chosen), selected = true, enabled = false) {}
        }
        if (inPhone.isEmpty()) Note(stringResource(R.string.dock_storage_none))
    }
    Section(stringResource(R.string.data_copy_title)) {
        Note(stringResource(R.string.dock_storage_upload))
        Note(stringResource(R.string.dock_storage_usb))
        view.inbox?.let { waiting ->
            Note(stringResource(R.string.dock_inbox_where))
            if (waiting.files > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringResource(R.plurals.dock_inbox_waiting, waiting.files, waiting.files, Formatter.formatShortFileSize(LocalContext.current, waiting.bytes)),
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = actions::takeInbox) { Text(stringResource(R.string.dock_inbox_take)) }
                }
            }
        }
    }
    Explain(stringResource(R.string.dock_storage_body))
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
    return stringResource(R.string.dock_storage_free, Formatter.formatShortFileSize(context, free), Formatter.formatShortFileSize(context, total))
}

/** A choice waiting for its confirmation: a card, or the phone when [volume] is null. */
private class Choice(val volume: DataVolumes.Volume?)

/**
 * Backup and restore (docs/PLAN.md §8.1, §16 10.8): the server's settings saved as an AES zip
 * where the owner picks, and restored from one ([BackupJob]). Saving and restoring are two blocks
 * of their own, so neither's fields are mistaken for the other's. The passphrase is typed each
 * time and kept nowhere but these fields; a new backup asks for it twice, since a mistyped one
 * would lock the owner out of it for good. A restore asks first, and only with the server stopped.
 */
@Composable
internal fun BackupPage(model: AppModel, actions: AppActions, onBack: () -> Unit) {
    PageHeader(stringResource(R.string.backup_title), onBack)
    val job = model.backup
    val working = job is BackupJob.State.Working || model.resetting
    val stopped = model.server is ServerState.Stopped || model.server is ServerState.Failed
    job?.let { Block { BackupOutcome(it) } }
    Section(stringResource(R.string.backup_save_title)) {
        var passphrase by remember { mutableStateOf("") }
        var again by remember { mutableStateOf("") }
        val ok = Backup.passphraseOk(passphrase.toCharArray())
        PassphraseField(passphrase, stringResource(R.string.dock_backup_passphrase)) { passphrase = it }
        Note(stringResource(R.string.dock_backup_rule))
        PassphraseField(again, stringResource(R.string.backup_again)) { again = it }
        Button(onClick = { actions.saveBackup(passphrase.toCharArray()) }, enabled = ok && again == passphrase && !working) {
            Text(stringResource(R.string.dock_backup_save))
        }
    }
    Section(stringResource(R.string.backup_restore_title)) {
        var passphrase by remember { mutableStateOf("") }
        var asking by remember { mutableStateOf(false) }
        val ok = Backup.passphraseOk(passphrase.toCharArray())
        PassphraseField(passphrase, stringResource(R.string.backup_restore_passphrase)) { passphrase = it }
        if (!stopped) Note(stringResource(R.string.dock_backup_stop_first))
        OutlinedButton(onClick = { asking = true }, enabled = ok && stopped && !working) { Text(stringResource(R.string.dock_backup_restore)) }
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
    Explain(stringResource(R.string.dock_backup_body))
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
            if (state.npmCode == 0) pluralStringResource(R.plurals.dock_backup_restored, state.files, state.files)
            else pluralStringResource(R.plurals.dock_backup_restored_no_plugins, state.files, state.files)
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

/**
 * The server's log (docs/PLAN.md §8.1, Logs): its newest lines as they come, narrowed to the
 * problems or to the app's own lines and to the words typed ([LogFilter]), and the diagnostics
 * bundle, shared through the share sheet ([fi.pocketsk.server.DiagnosticsBundle]) after saying
 * what is in it and what is not.
 */
@Composable
internal fun LogPage(model: AppModel, actions: AppActions, onBack: () -> Unit) {
    PageHeader(stringResource(R.string.dock_log_title), onBack)
    Block {
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
    }
    Section(stringResource(R.string.log_share_title)) {
        Note(stringResource(R.string.dock_log_share_why))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions::shareDiagnostics, enabled = !model.sharing) { Text(stringResource(R.string.dock_log_share)) }
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
 * Native addons installed without an Android build ([fi.pocketsk.install.MissingBuilds],
 * docs/PLAN.md §16 12.4): the plugins that need them do not load, which the server's log only says
 * when it loads them. Each can be asked for, and is put in place at the next install once the
 * Release has it.
 */
@Composable
internal fun MissingPage(model: AppModel, actions: AppActions, onBack: () -> Unit) {
    PageHeader(stringResource(R.string.dock_missing_title), onBack)
    Block {
        Text(stringResource(R.string.dock_missing_text))
        for (m in model.missingBuilds) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.dock_missing_package, m.name, m.version), modifier = Modifier.weight(1f))
                TextButton(onClick = { actions.requestBuild(m) }) { Text(stringResource(R.string.dock_missing_request)) }
            }
        }
        Note(stringResource(R.string.dock_missing_after))
    }
}

/**
 * Reset (docs/PLAN.md §16 15): the server's settings back to a first start with the data kept, or
 * everything back to a fresh install of the app ([ResetJob]). Two blocks, each saying what goes
 * and what stays before its button, and each asking again; the backup is a row away.
 */
@Composable
internal fun ResetPage(model: AppModel, actions: AppActions, onBack: () -> Unit) {
    PageHeader(stringResource(R.string.reset_title), onBack)
    var asked by remember { mutableStateOf<ResetJob.Kind?>(null) }
    val busy = model.installing || model.backup is BackupJob.State.Working || model.resetting
    model.reset?.let { Block { ResetOutcome(it) } }
    Note(stringResource(R.string.reset_body))
    Section(stringResource(R.string.reset_settings_title)) {
        Note(stringResource(R.string.reset_settings_what))
        OutlinedButton(onClick = { asked = ResetJob.Kind.SETTINGS }, enabled = !busy) { Text(stringResource(R.string.reset_settings)) }
    }
    Section(stringResource(R.string.reset_everything_title)) {
        Note(stringResource(R.string.reset_everything_what))
        Button(
            onClick = { asked = ResetJob.Kind.EVERYTHING },
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) { Text(stringResource(R.string.reset_everything)) }
    }
    asked?.let { kind ->
        val everything = kind == ResetJob.Kind.EVERYTHING
        AlertDialog(
            onDismissRequest = { asked = null },
            title = { Text(stringResource(if (everything) R.string.reset_everything_title else R.string.reset_settings_title)) },
            text = { Text(stringResource(if (everything) R.string.reset_everything_text else R.string.reset_settings_text)) },
            confirmButton = {
                TextButton(onClick = {
                    asked = null
                    if (everything) actions.resetEverything() else actions.resetSettings()
                }) {
                    Text(stringResource(if (everything) R.string.reset_everything_go else R.string.reset_settings_go), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { asked = null }) { Text(stringResource(R.string.dock_storage_cancel)) } },
        )
    }
}

/** How far the reset running is, or what the last one came to. */
@Composable
private fun ResetOutcome(state: ResetJob.State) {
    val text = when (state) {
        is ResetJob.State.Stopping -> stringResource(R.string.reset_stopping)
        is ResetJob.State.Working -> stringResource(if (state.kind == ResetJob.Kind.EVERYTHING) R.string.reset_everything_working else R.string.reset_settings_working)
        is ResetJob.State.Done -> stringResource(if (state.started) R.string.reset_settings_done_started else R.string.reset_settings_done)
        is ResetJob.State.Failed -> when (state.why) {
            ResetJob.Why.BUSY -> stringResource(R.string.reset_busy)
            ResetJob.Why.STILL_RUNNING -> stringResource(R.string.reset_still_running)
            ResetJob.Why.FAILED -> stringResource(R.string.reset_failed, state.detail)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (state is ResetJob.State.Stopping || state is ResetJob.State.Working) CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp).size(24.dp))
        Text(text, color = if (state is ResetJob.State.Failed) MaterialTheme.colorScheme.error else Color.Unspecified)
    }
}

private const val INSTALL_LINES = 15
private const val HISTORY_LINES = 5

/** The releases the notes show at most, newest first; the rest are a line and a link to GitHub. */
private const val NOTE_RELEASES = 10

/** A release's lines shown: the head of a long list of changes, the rest counted. */
private const val NOTE_LINES = 12
