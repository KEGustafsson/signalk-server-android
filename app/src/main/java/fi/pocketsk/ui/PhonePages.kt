package fi.pocketsk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fi.pocketsk.BuildConfig
import fi.pocketsk.R
import fi.pocketsk.power.PowerProfile
import fi.pocketsk.server.BackupJob
import fi.pocketsk.update.AppRelease
import fi.pocketsk.update.AppUpdater

/**
 * The helm screen (docs/PLAN.md §8.3): the red night filter and its strength, the screen dimmed
 * below the phone's own lowest, and the screen kept on, over every app ([fi.pocketsk.night.NightFilter]).
 * Each change shows at once, so the owner sets them looking at the screen; the Quick Settings tile
 * turns the filter on and off at the helm. They need the app allowed to draw over other apps,
 * which the page asks for first.
 */
@Composable
internal fun HelmPage(model: AppModel, actions: AppActions, onBack: () -> Unit) {
    PageHeader(stringResource(R.string.dock_helm_title), onBack)
    // Read for what is shown; each change copies model.night itself, so two changes before the
    // next recomposition do not undo each other.
    val night = model.night
    if (!model.nightAllowed) {
        Block {
            Problem(stringResource(R.string.dock_helm_overlay_why))
            Button(onClick = actions::openOverlaySettings) { Text(stringResource(R.string.dock_helm_overlay_allow)) }
        }
        Explain(stringResource(R.string.dock_helm_body))
        return
    }
    Block {
        SwitchRow(stringResource(R.string.dock_helm_filter), night.filter) { actions.setNight(model.night.copy(filter = it)) }
        if (night.filter) {
            PercentSlider(stringResource(R.string.dock_helm_strength, night.strength), night.strength, 0) { actions.setNight(model.night.copy(strength = it)) }
        }
    }
    Block {
        SwitchRow(stringResource(R.string.dock_helm_dim), night.dim) { actions.setNight(model.night.copy(dim = it)) }
        if (night.dim) {
            PercentSlider(stringResource(R.string.dock_helm_brightness, night.brightness), night.brightness, 1) { actions.setNight(model.night.copy(brightness = it)) }
        }
    }
    Block {
        SwitchRow(stringResource(R.string.dock_helm_keep_on), night.keepOn) { actions.setNight(model.night.copy(keepOn = it)) }
    }
    Explain(stringResource(R.string.dock_helm_body))
}

/**
 * The power profiles (docs/PLAN.md §8.1): what the phone spends on the server, which runs in all
 * three ([fi.pocketsk.power.PowerRules]). Each choice says what it does, since the names alone do
 * not; Passage dims with the helm screen's overlay, so the page says when that is not allowed.
 */
@Composable
internal fun PowerPage(model: AppModel, actions: AppActions, onBack: () -> Unit) {
    PageHeader(stringResource(R.string.dock_power_title), onBack)
    Note(stringResource(R.string.dock_power_body))
    Block {
        for (p in PowerProfile.entries) {
            val what = when (p) {
                PowerProfile.HARBOR -> R.string.dock_power_harbor_what
                PowerProfile.PASSAGE -> R.string.dock_power_passage_what
                PowerProfile.SAVER -> R.string.dock_power_saver_what
            }
            ChoiceRow(stringResource(powerName(p)), model.power == p, stringResource(what)) { if (model.power != p) actions.setPower(p) }
        }
        if (model.power == PowerProfile.PASSAGE && !model.nightAllowed) {
            Problem(stringResource(R.string.dock_power_passage_overlay))
            TextButton(onClick = actions::openOverlaySettings) { Text(stringResource(R.string.dock_helm_overlay_allow)) }
        }
    }
}

/** What the server needs from the phone, each with its state and a way to grant it. */
@Composable
internal fun PermissionsPage(model: AppModel, actions: AppActions, onBack: () -> Unit) {
    PageHeader(stringResource(R.string.permissions_title), onBack)
    Block { PermissionRows(model.permissions, actions) }
    Explain(stringResource(R.string.setup_permissions_text))
}

/**
 * The app's own update (docs/PLAN.md §10): whether a newer build is published, its download, and
 * what Android's installer or the checks before it said. Offered only where the app updates
 * itself, not where a store keeps it current.
 */
@Composable
internal fun AppUpdatePage(model: AppModel, actions: AppActions, onBack: () -> Unit) {
    PageHeader(stringResource(R.string.dock_app_update_title), onBack)
    val state = model.app ?: return
    Block {
        Text(stringResource(R.string.dock_app_installed, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.titleMedium)
        val offered = when (state) {
            is AppUpdater.State.Available -> state.release
            is AppUpdater.State.Failed -> state.release
            is AppUpdater.State.Refused -> state.release
            else -> null
        }
        when (state) {
            AppUpdater.State.Idle, is AppUpdater.State.Available -> Unit
            AppUpdater.State.Checking -> CircularProgressIndicator()
            AppUpdater.State.UpToDate -> Note(stringResource(R.string.dock_app_up_to_date))
            is AppUpdater.State.Downloading -> {
                if (state.total > 0) LinearProgressIndicator(progress = { state.done.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.dock_app_downloading, state.release.versionName))
            }
            is AppUpdater.State.Installing -> Text(stringResource(R.string.dock_app_installing, state.release.versionName))
            is AppUpdater.State.Failed -> Problem(stringResource(R.string.dock_app_failed, state.reason))
            is AppUpdater.State.Refused -> Problem(refusalText(state.refusal, state.release.versionName))
        }
        offered?.let { release ->
            // The installer replaces the app's process, which runs an install, a backup, a restore or a reset.
            val busy = model.installing || model.resetting || model.backup is BackupJob.State.Working

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions::updateApp, enabled = !busy) { Text(stringResource(R.string.dock_app_update, release.versionName)) }
                TextButton(onClick = actions::openAppRelease) { Text(stringResource(R.string.dock_app_whats_new)) }
            }
            if (busy) Note(stringResource(R.string.app_update_busy))
            if (!model.appInstallsAllowed) Note(stringResource(R.string.dock_app_allow))
        }
    }
    Block {
        // A look now, whether or not the daily one is on, for the app alone.
        OutlinedButton(
            onClick = actions::checkAppUpdate,
            enabled = state !is AppUpdater.State.Checking && !model.appUpdating,
        ) { Text(stringResource(R.string.app_check_now)) }
        SwitchRow(stringResource(R.string.dock_app_checks), model.appChecks, onChange = actions::setAppChecks)
    }
}

@Composable
private fun refusalText(refusal: AppRelease.Refusal, version: String): String = stringResource(
    when (refusal) {
        AppRelease.Refusal.CHECKSUM -> R.string.dock_app_refused_checksum
        AppRelease.Refusal.UNREADABLE -> R.string.dock_app_refused_unreadable
        AppRelease.Refusal.OTHER_APP -> R.string.dock_app_refused_other_app
        AppRelease.Refusal.NOT_NEWER -> R.string.dock_app_refused_not_newer
        AppRelease.Refusal.OTHER_SIGNER -> R.string.dock_app_refused_other_signer
    },
    version,
)
