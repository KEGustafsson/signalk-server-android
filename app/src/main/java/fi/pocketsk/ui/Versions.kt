package fi.pocketsk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fi.pocketsk.R
import fi.pocketsk.install.InstallHistory
import fi.pocketsk.install.InstallManager
import fi.pocketsk.install.VersionChoices
import fi.pocketsk.install.VersionChoices.Note

/** The chooser's picks that are not a version from npm. */
private const val FROM_FILE = "\u0000file"
private const val FROM_WEB = "\u0000web"

/**
 * Which version to install, for the setup wizard and the Updates card's "Other versions" (docs/PLAN.md
 * §5.3, §8.1, §16 15): npm's releases newest first, the beta and test releases too when asked for,
 * each saying what it is ([VersionChoices]); a `.tgz` made by `npm pack` on any computer, for a boat
 * with no internet; or the web address of a `.tgz` build. Before the button, what the pick means:
 * a beta is for trying out, the version running is installed again, the kept one is a rollback
 * away, and one rolled back on this phone is installed only when asked twice.
 *
 * [live] and [kept] are the versions on the phone, null in the wizard. [onInstall] gets the source
 * and whether the owner chose a version the health gate rolled back.
 */
@Composable
internal fun VersionChooser(
    model: AppModel,
    actions: AppActions,
    live: String?,
    kept: String?,
    history: InstallHistory,
    onInstall: (InstallManager.Source, Boolean) -> Unit,
) {
    LaunchedEffect(Unit) { actions.loadVersions(refresh = false) }
    var prereleases by rememberSaveable { mutableStateOf(false) }
    var all by rememberSaveable { mutableStateOf(false) }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var address by rememberSaveable { mutableStateOf("") }
    val choices = (model.available as? Available.Loaded)?.let { VersionChoices.list(it.versions, prereleases, live, kept, history) }.orEmpty()
    // The latest release until another is picked; derived, not written back during composition.
    val choice = picked?.takeIf { p -> p == FROM_FILE || p == FROM_WEB || choices.any { it.version.text == p } }
        ?: VersionChoices.default(choices)?.version?.text
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().toggleable(value = prereleases, role = Role.Switch, onValueChange = { prereleases = it }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.versions_prereleases), modifier = Modifier.weight(1f))
            Switch(checked = prereleases, onCheckedChange = null)
        }
        when (val available = model.available) {
            Available.Unknown, Available.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                Text(stringResource(R.string.setup_version_loading))
            }
            is Available.Failed -> {
                Text(stringResource(R.string.setup_version_failed, available.reason), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { actions.loadVersions(refresh = true) }) { Text(stringResource(R.string.setup_retry)) }
            }
            is Available.Loaded -> {
                val shown = if (all) choices else choices.take(SHOWN_VERSIONS)
                for (c in shown) Pick(label(c), selected = choice == c.version.text, onSelect = { picked = c.version.text })
                if (!all && choices.size > SHOWN_VERSIONS) {
                    TextButton(onClick = { all = true }) { Text(pluralStringResource(R.plurals.versions_all, choices.size, choices.size)) }
                }
            }
        }
        val fileLabel = model.tarballName?.let { stringResource(R.string.setup_version_file_named, it) }
            ?: stringResource(R.string.setup_version_file)
        Pick(fileLabel, selected = choice == FROM_FILE, onSelect = {
            picked = FROM_FILE
            if (model.tarball == null) actions.pickTarball()
        })
        if (choice == FROM_FILE) {
            TextButton(onClick = actions::pickTarball) { Text(stringResource(R.string.setup_version_file_pick)) }
        }
        Pick(stringResource(R.string.versions_web), selected = choice == FROM_WEB, onSelect = { picked = FROM_WEB })
        if (choice == FROM_WEB) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text(stringResource(R.string.versions_web_address)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.versions_web_hint), style = MaterialTheme.typography.bodySmall)
        }
        val chosen = choices.firstOrNull { it.version.text == choice }
        val source = when (choice) {
            null -> null
            FROM_FILE -> model.tarball?.let { InstallManager.Source.Tarball(it) }
            FROM_WEB -> VersionChoices.webAddress(address)?.let { InstallManager.Source.Url(it) }
            else -> InstallManager.Source.Registry(choice)
        }
        chosen?.let { Warnings(it) }
        val bad = chosen != null && Note.ROLLED_BACK in chosen.notes
        Button(enabled = source != null && !model.installing && !model.resetting, onClick = { if (source != null) onInstall(source, bad) }) {
            Text(
                when {
                    chosen == null -> stringResource(R.string.setup_install)
                    bad -> stringResource(R.string.versions_install_anyway, chosen.version.text)
                    Note.INSTALLED in chosen.notes -> stringResource(R.string.versions_reinstall, chosen.version.text)
                    else -> stringResource(R.string.dock_install, chosen.version.text)
                },
            )
        }
    }
}

/** A version and what it is: "2.35.1 · latest release · running now". */
@Composable
private fun label(c: VersionChoices.Choice): String {
    val notes = c.notes.sortedBy { it.ordinal }.map {
        stringResource(
            when (it) {
                Note.LATEST -> R.string.versions_note_latest
                Note.INSTALLED -> R.string.versions_note_installed
                Note.KEPT -> R.string.versions_note_kept
                Note.PRERELEASE -> R.string.versions_note_prerelease
                Note.ROLLED_BACK -> R.string.versions_note_rolled_back
            },
        )
    }
    return (listOf(c.version.text) + notes).joinToString(" · ")
}

/** What the owner should know about the version picked, before the button. */
@Composable
private fun Warnings(c: VersionChoices.Choice) {
    val v = c.version.text
    if (Note.PRERELEASE in c.notes) Text(stringResource(R.string.versions_warn_prerelease, v), style = MaterialTheme.typography.bodySmall)
    if (Note.ROLLED_BACK in c.notes) {
        Text(stringResource(R.string.versions_warn_rolled_back, v), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (Note.INSTALLED in c.notes) Text(stringResource(R.string.versions_warn_installed, v), style = MaterialTheme.typography.bodySmall)
    if (Note.KEPT in c.notes) Text(stringResource(R.string.versions_warn_kept, v), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Pick(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

private const val SHOWN_VERSIONS = 6
