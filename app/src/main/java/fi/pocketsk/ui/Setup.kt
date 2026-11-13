package fi.pocketsk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fi.pocketsk.R
import fi.pocketsk.install.InstallHistory
import fi.pocketsk.install.InstallManager

/** The wizard's pages, in order (docs/PLAN.md §5.3). */
internal enum class SetupStep { WELCOME, VERSION, INSTALL, PERMISSIONS, DONE }

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
        // Where in the wizard the owner is, so its length is known from its first page.
        val steps = SetupStep.entries.size
        LinearProgressIndicator(progress = { (step.ordinal + 1f) / steps }, modifier = Modifier.fillMaxWidth())
        Note(stringResource(R.string.setup_step, step.ordinal + 1, steps))
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
 * Which version: the latest release unless another is picked from npm's list, the beta and test
 * releases among them when asked for, a `.tgz` made by `npm pack signalk-server` on any computer
 * for a boat with no internet (§5.1), or the web address of a build ([VersionChooser]).
 */
@Composable
private fun VersionPage(model: AppModel, actions: AppActions, onInstall: () -> Unit) {
    Section(stringResource(R.string.setup_version_title)) {
        VersionChooser(model, actions, live = null, kept = null, history = model.slots?.history ?: InstallHistory()) { source, allowBad ->
            actions.install(source, allowBad)
            onInstall()
        }
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

private const val TAIL_LINES = 40
