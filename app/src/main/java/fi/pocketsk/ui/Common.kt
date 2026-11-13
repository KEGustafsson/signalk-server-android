package fi.pocketsk.ui

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.pocketsk.R
import fi.pocketsk.install.InstallManager
import fi.pocketsk.server.ServerState
import kotlin.math.roundToInt

/** The rounded corners every block of the Dock shares. */
private val BLOCK = RoundedCornerShape(20.dp)

/** One block of a page, or one page of the wizard, with its title. */
@Composable
internal fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Block(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        content()
    }
}

/** A block without a title: a rounded card lifted from the surface, its content spaced evenly. */
@Composable
internal fun Block(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = BLOCK,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/**
 * A page's head: back to the tab it opened from, and the page's name. A page holds one subject
 * (docs/PLAN.md §16 16), so its name is all the head says.
 */
@Composable
internal fun PageHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.nav_back))
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp).semantics { heading() })
    }
}

/** A tab's head: its name, large, as the bottom bar names it. */
@Composable
internal fun TabHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** Rows one under another in one block, a hairline between them, the way a tab lists its pages. */
@Composable
internal fun RowGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = BLOCK,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(content = content)
    }
}

/** The hairline between two rows of a [RowGroup]. */
@Composable
internal fun RowDivider() {
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest)
}

/**
 * A row that opens a page: its name and, under it, how that subject stands now ("2.35.1 · up to
 * date"), so a tab read top to bottom says everything without a page opened. [alert] colours the
 * line when something there is wrong, [accent] when something is on offer, [danger] the name of a
 * page whose actions cannot be undone.
 */
@Composable
internal fun NavRow(
    title: String,
    summary: String?,
    onClick: () -> Unit,
    alert: Boolean = false,
    accent: Boolean = false,
    danger: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
            summary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        alert -> MaterialTheme.colorScheme.error
                        accent -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Icon(
            painterResource(R.drawable.ic_chevron),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * A switch with its words: the whole row is the switch, so a screen reader reads the label with
 * the state and a tap on the words works. [detail] says what on means, under the label.
 */
@Composable
internal fun SwitchRow(label: String, checked: Boolean, detail: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** One of several choices, the whole row the radio button, with what the choice does under its name. */
@Composable
internal fun ChoiceRow(label: String, selected: Boolean, detail: String? = null, enabled: Boolean = true, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(label)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** A percentage from [min] to 100, in whole steps; the slider carries its label for a screen reader. */
@Composable
internal fun PercentSlider(label: String, value: Int, min: Int, onChange: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.clearAndSetSemantics {})
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.roundToInt()) },
        valueRange = min.toFloat()..100f,
        modifier = Modifier.semantics { contentDescription = label },
    )
}

/** A quiet line under a control: what it does or what follows from it. */
@Composable
internal fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A line that something went wrong, or is wrong now. */
@Composable
internal fun Problem(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error)
}

/**
 * The longer explanation of a page, folded under "How this works" until asked for: the owner who
 * knows it sees the controls alone, the one who does not is one tap from the whole story.
 */
@Composable
internal fun Explain(text: String) {
    var open by rememberSaveable(text) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, modifier = Modifier.padding(start = 0.dp)) {
        Text(stringResource(if (open) R.string.explain_hide else R.string.explain_show))
    }
    if (open) Note(text)
}

/** A volume's free room in a few words: "12 GB free". */
@Composable
internal fun freeText(free: Long): String =
    stringResource(R.string.data_free, Formatter.formatShortFileSize(LocalContext.current, free))

/** Lines of output, small and monospaced, as npm and the server print them. */
@Composable
internal fun LogText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
}

/** The server's state in a line, as the Home ring's caption and the wizard's last page say it. */
@Composable
internal fun serverStateText(state: ServerState, url: String): String = when (state) {
    ServerState.Stopped -> stringResource(R.string.dock_stopped)
    is ServerState.Starting -> stringResource(R.string.dock_starting, state.version)
    is ServerState.Running -> when {
        !state.answering -> stringResource(R.string.server_not_answering, url)
        state.onTrial -> stringResource(R.string.dock_running_on_trial, state.version)
        else -> stringResource(R.string.dock_running, state.version)
    }
    is ServerState.Waiting -> stringResource(R.string.server_waiting, state.reason, state.delayMs / 1000)
    is ServerState.Storage -> when {
        state.waiting -> stringResource(R.string.server_storage_waiting, state.volume ?: "")
        state.volume == null -> stringResource(R.string.server_storage_moving_phone)
        else -> stringResource(R.string.server_storage_moving, state.volume)
    }
    ServerState.Stopping -> stringResource(R.string.server_stopping)
    is ServerState.Failed -> stringResource(R.string.dock_failed, state.reason)
}

/** An install's progress in a line. */
@Composable
internal fun installStateText(state: InstallManager.State?): String = when (state) {
    null -> stringResource(R.string.setup_install_preparing)
    is InstallManager.State.Installing -> stringResource(R.string.setup_install_npm)
    is InstallManager.State.Verifying -> stringResource(R.string.setup_install_verifying)
    is InstallManager.State.Done -> stringResource(R.string.setup_install_done, state.version)
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
 * wizard's permission page and the Phone tab's Permissions page (docs/PLAN.md §5.3 step 4).
 * Location is the bridge's (the phone's GNSS as a Signal K source, §7.1) and asked for on its own,
 * since the server starts without it. Full-screen alarms are the bridge's too (the boat's alarms
 * waking a locked phone), and the alarm rings without them.
 */
@Composable
internal fun PermissionRows(permissions: PermissionsView, actions: AppActions) {
    val missing = permissions.notifications == false || permissions.localNetwork == false
    permissions.notifications?.let { PermissionRow(stringResource(R.string.perm_notifications), it) }
    permissions.localNetwork?.let { PermissionRow(stringResource(R.string.perm_local_network), it) }
    if (missing) {
        OutlinedButton(onClick = actions::askPermissions) { Text(stringResource(R.string.perm_allow)) }
    }
    PermissionRow(stringResource(R.string.perm_location), permissions.location)
    if (!permissions.location) {
        Note(stringResource(R.string.perm_location_why))
        OutlinedButton(onClick = actions::askLocation) { Text(stringResource(R.string.perm_location_allow)) }
    }
    permissions.fullScreen?.let { granted ->
        PermissionRow(stringResource(R.string.perm_full_screen), granted)
        if (!granted) {
            Note(stringResource(R.string.perm_full_screen_why))
            OutlinedButton(onClick = actions::openFullScreenSettings) { Text(stringResource(R.string.perm_full_screen_open)) }
        }
    }
    PermissionRow(stringResource(R.string.perm_battery), permissions.batteryUnrestricted)
    if (!permissions.batteryUnrestricted) {
        Note(stringResource(R.string.perm_battery_why))
        OutlinedButton(onClick = actions::openBatterySettings) { Text(stringResource(R.string.perm_battery_open)) }
    }
    SwitchRow(stringResource(R.string.perm_autostart), permissions.autostart, onChange = actions::setAutostart)
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
