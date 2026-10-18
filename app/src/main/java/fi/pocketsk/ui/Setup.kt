package fi.pocketsk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import fi.pocketsk.R
import fi.pocketsk.install.InstallManager

/** The wizard's pages, in order (docs/PLAN.md §5.3). */
internal enum class SetupStep { WELCOME, VERSION, INSTALL, PERMISSIONS, DONE }

/** The version page's choice of a file rather than a version from npm. */
private const val FROM_FILE = "file"

/**
 * The first-run wizard: what will be downloaded, which version, the install with its live log,
 * what the server needs from the phone, and the start. Shown until it has been finished once
 * ([AppActions.finishSetup]); an install already running when the screen comes back (a rotation,
 * the app reopened) is shown at once, rather than the welcome page.
 */
@Composable
internal fun SetupScreen(model: AppModel, actions: AppActions, modifier: Modifier = Modifier) {
    var step by rememberSaveable { mutableStateOf(if (model.installing) SetupStep.INSTALL else SetupStep.WELCOME) }
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
        when (step) {
            SetupStep.WELCOME -> WelcomePage(onNext = { step = SetupStep.VERSION })
            SetupStep.VERSION -> VersionPage(model, actions, onInstall = { step = SetupStep.INSTALL })
            SetupStep.INSTALL -> InstallPage(
                model,
                onBack = { step = SetupStep.VERSION },
                onNext = { step = SetupStep.PERMISSIONS },
            )
            SetupStep.PERMISSIONS -> Section(stringResource(R.string.setup_permissions_title)) {
                Text(stringResource(R.string.setup_permissions_text))
                PermissionRows(model.permissions, actions)
                Button(onClick = { step = SetupStep.DONE }) { Text(stringResource(R.string.setup_next)) }
            }
            SetupStep.DONE -> Section(stringResource(R.string.setup_done_title)) {
                Text(stringResource(R.string.setup_done_text))
                Button(onClick = { actions.finishSetup(startServer = true) }) { Text(stringResource(R.string.setup_done_start)) }
                TextButton(onClick = { actions.finishSetup(startServer = false) }) { Text(stringResource(R.string.setup_done_later)) }
            }
        }
    }
}

@Composable
private fun WelcomePage(onNext: () -> Unit) {
    Section(stringResource(R.string.setup_welcome_title)) {
        Text(stringResource(R.string.setup_welcome_text))
        Button(onClick = onNext) { Text(stringResource(R.string.setup_next)) }
    }
}

/**
 * Which version: the newest releases npm lists, the latest chosen until another is, or a `.tgz`
 * made by `npm pack signalk-server` on any computer, for a boat with no internet (§5.1).
 */
@Composable
private fun VersionPage(model: AppModel, actions: AppActions, onInstall: () -> Unit) {
    LaunchedEffect(Unit) { actions.loadVersions(refresh = false) }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    // The latest release until another is picked; derived, not written back during composition.
    val choice = picked ?: (model.available as? Available.Loaded)?.versions?.firstOrNull()?.text
    Section(stringResource(R.string.setup_version_title)) {
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
                available.versions.take(SHOWN_VERSIONS).forEachIndexed { i, v ->
                    val label = if (i == 0) stringResource(R.string.setup_version_latest, v.text) else v.text
                    Choice(label, selected = choice == v.text, onSelect = { picked = v.text })
                }
            }
        }
        val fileLabel = model.tarballName?.let { stringResource(R.string.setup_version_file_named, it) }
            ?: stringResource(R.string.setup_version_file)
        Choice(fileLabel, selected = choice == FROM_FILE, onSelect = {
            picked = FROM_FILE
            if (model.tarball == null) actions.pickTarball()
        })
        if (choice == FROM_FILE) {
            TextButton(onClick = actions::pickTarball) { Text(stringResource(R.string.setup_version_file_pick)) }
        }
        val source = when (choice) {
            null -> null
            FROM_FILE -> model.tarball?.let { InstallManager.Source.Tarball(it) }
            else -> InstallManager.Source.Registry(choice)
        }
        Button(
            enabled = source != null && !model.installing,
            onClick = {
                if (source != null) {
                    actions.install(source)
                    onInstall()
                }
            },
        ) { Text(stringResource(R.string.setup_install)) }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

/** The install with its live log; on to the permissions once it is live, back to the choice if it failed. */
@Composable
private fun InstallPage(model: AppModel, onBack: () -> Unit, onNext: () -> Unit) {
    val state = model.install
    Section(stringResource(R.string.setup_install_title)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (model.installing) CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
            Text(installStateText(state))
        }
        installTail(state).takeIf { it.isNotEmpty() }?.let { LogText(it.lines().takeLast(TAIL_LINES).joinToString("\n")) }
        when {
            model.installing -> Unit
            state is InstallManager.State.Done -> Button(onClick = onNext) { Text(stringResource(R.string.setup_next)) }
            state is InstallManager.State.Failed -> OutlinedButton(onClick = onBack) { Text(stringResource(R.string.setup_back)) }
        }
    }
}

private const val SHOWN_VERSIONS = 5
private const val TAIL_LINES = 40
