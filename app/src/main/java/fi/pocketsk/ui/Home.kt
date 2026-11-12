package fi.pocketsk.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fi.pocketsk.BuildConfig
import fi.pocketsk.R
import fi.pocketsk.ServerActions
import fi.pocketsk.bridge.BridgeProtocol
import fi.pocketsk.install.InstallManager
import fi.pocketsk.power.PowerProfile
import fi.pocketsk.satellite.SatelliteJob
import fi.pocketsk.server.ServerState
import fi.pocketsk.server.ServerUsage
import fi.pocketsk.update.AppUpdater
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Home (docs/PLAN.md §8.1, §16 16): the boat's alarms while there are any; the server as a ring,
 * coloured by its state and turning while data comes in, with the one thing to do with it now
 * (start it, or open its admin UI); where tablets reach it; what needs the owner ([Needs]), each a
 * card with its one button; the webapps as tiles; and the helm's two quick controls, the night
 * filter and the power profile. Everything else is a tab away.
 */
@Composable
internal fun HomeTab(model: AppModel, actions: AppActions, needs: List<Needs.Need>, open: (Page) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        // A developer's tool (the runtime checks), in debug builds only.
        if (BuildConfig.DEBUG) {
            TextButton(onClick = actions::openRuntimeChecks) { Text(stringResource(R.string.dock_runtime_checks)) }
        }
    }
    if (model.alarms.alarms.isNotEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.dock_alarms_title), style = MaterialTheme.typography.titleMedium)
                AlarmRows(model.alarms, actions::silenceAlarm)
            }
        }
    }
    StatusRing(model)
    MainAction(model, actions, open)
    Reach(model, open)
    if (needs.isNotEmpty()) {
        Text(stringResource(R.string.needs_title), style = MaterialTheme.typography.titleMedium)
        for (need in needs) NeedCard(need, model, actions, open)
    } else if (model.server is ServerState.Running) {
        Note(stringResource(R.string.needs_none))
    }
    Webapps(model, actions)
    HelmStrip(model, actions, open)
}

/** How the server stands, as the ring's colour. */
private fun tone(state: ServerState): Tone = when (state) {
    is ServerState.Running -> if (state.answering) Tone.RUNNING else Tone.BUSY
    is ServerState.Starting, is ServerState.Waiting, is ServerState.Storage, ServerState.Stopping -> Tone.BUSY
    ServerState.Stopped -> Tone.STOPPED
    is ServerState.Failed -> Tone.FAILED
}

/** The ring's word for the server's state: one word, large; the caption under it says the rest. */
private fun stateWord(state: ServerState): Int = when (state) {
    is ServerState.Running -> if (state.answering) R.string.ring_running else R.string.ring_starting
    is ServerState.Starting -> R.string.ring_starting
    is ServerState.Waiting -> R.string.ring_waiting
    is ServerState.Storage -> R.string.ring_moving
    ServerState.Stopping -> R.string.ring_stopping
    ServerState.Stopped -> R.string.ring_stopped
    is ServerState.Failed -> R.string.ring_failed
}

/**
 * The server as a ring: a full ring in its state's colour, and while it runs a brighter arc that
 * turns, faster while data comes in (the bridge's delta rate) than while none does, so a glance
 * at the phone at the chart table says the server is alive and fed. Inside it, the state, the
 * version and how long it has run. A screen reader hears the state's line, not the drawing.
 */
@Composable
private fun StatusRing(model: AppModel) {
    val state = model.server
    val color = tone(state).color()
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val running = state is ServerState.Running && state.answering
    val busy = tone(state) == Tone.BUSY
    val flowing = (model.deltaRate ?: 0.0) > 0.0
    val turn = rememberInfiniteTransition(label = "ring")
    val angle by turn.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (flowing) TURN_FED_MS else TURN_IDLE_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "angle",
    )
    val caption = serverStateText(state, ServerActions.localUrl((state as? ServerState.Running)?.port ?: ServerActions.port()))
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = caption },
    ) {
        Canvas(modifier = Modifier.size(RING_SIZE.dp)) {
            val stroke = RING_STROKE.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            when {
                running -> {
                    drawArc(color.copy(alpha = 0.35f), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                    drawArc(color, angle - 90f, 70f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                busy -> drawArc(color, angle * 2 - 90f, 110f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                else -> drawArc(color.copy(alpha = 0.8f), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clearAndSetSemantics {}) {
            Text(stringResource(stateWord(state)), style = MaterialTheme.typography.headlineSmall, color = color)
            val version = when (state) {
                is ServerState.Running -> state.version
                is ServerState.Starting -> state.version
                is ServerState.Waiting -> state.version
                else -> model.slots?.liveVersion
            }
            version?.let { Text(stringResource(R.string.ring_version, it), style = MaterialTheme.typography.bodyMedium) }
            model.usage?.takeIf { state is ServerState.Running }?.let {
                Text(stringResource(R.string.dock_uptime, DateUtils.formatElapsedTime(it.up / 1000)), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    // The caption says what the word cannot: why it waits or failed, a server not answering yet.
    val plain = state is ServerState.Stopped || (state is ServerState.Running && state.answering && !state.onTrial) || state is ServerState.Starting
    if (!plain) {
        Text(
            caption,
            textAlign = TextAlign.Center,
            color = if (state is ServerState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (state is ServerState.Running) {
        model.usage?.let { usage ->
            Text(
                usageLine(usage, model.deltaRate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** "CPU 4 % · 182 MB · 12 deltas/s", as much of it as is known. */
@Composable
private fun usageLine(u: ServerUsage.Usage, rate: Double?): String {
    val parts = listOfNotNull(
        u.cpuPercent?.let { stringResource(R.string.usage_cpu, it.roundToInt()) },
        u.memoryBytes?.let { stringResource(R.string.usage_memory, Formatter.formatShortFileSize(LocalContext.current, it)) },
        rate?.let { stringResource(R.string.usage_deltas, deltaRateText(it)) },
    )
    return parts.joinToString(" · ")
}

/** A delta rate as a person reads it: a tenth under ten a second ("2.4"), whole above ("118"). */
private fun deltaRateText(rate: Double): String =
    if (rate < 10) String.format(Locale.getDefault(), "%.1f", rate) else rate.roundToInt().toString()

/**
 * The one thing to do with the server now, large: install one, start it, or open its admin UI.
 * Restart and Stop sit beside the admin UI's button, smaller, since they are not what the owner
 * comes for.
 */
@Composable
private fun MainAction(model: AppModel, actions: AppActions, open: (Page) -> Unit) {
    val state = model.server
    val big = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    when {
        model.slots != null && model.slots?.liveVersion == null && !model.installing ->
            Button(onClick = { open(Page.VERSIONS) }, modifier = big) { Text(stringResource(R.string.home_install)) }
        model.installing -> OutlinedButton(onClick = { open(Page.VERSIONS) }, modifier = big) { Text(installStateText(model.install)) }
        !state.isActive -> Button(onClick = actions::startServer, enabled = model.slots?.liveVersion != null && !model.resetting, modifier = big) {
            Text(stringResource(R.string.home_start))
        }
        else -> {
            Button(onClick = actions::openAdmin, enabled = state is ServerState.Running && state.answering, modifier = big) {
                Text(stringResource(R.string.dock_open_admin))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = actions::restartServer, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.server_action_restart)) }
                OutlinedButton(onClick = actions::stopServer, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.server_action_stop)) }
            }
        }
    }
}

/**
 * Where the boat reaches the running server: the address a tablet or plotter types, large and
 * selectable, or that only this phone does, with the way to change it.
 */
@Composable
private fun Reach(model: AppModel, open: (Page) -> Unit) {
    val running = model.server as? ServerState.Running ?: return
    Block {
        // The run's own, not the setting's: a change takes effect with the restart it asks for.
        val lan = model.lanAddress?.takeIf { running.lan }
        if (lan != null) {
            Text(stringResource(R.string.reach_title), style = MaterialTheme.typography.labelLarge)
            SelectionContainer {
                Text("http://$lan:${running.port}", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
            }
        } else {
            Text(stringResource(if (running.lan) R.string.reach_no_network else R.string.reach_phone_only), style = MaterialTheme.typography.labelLarge)
        }
        Note(stringResource(R.string.dock_url_phone, ServerActions.localUrl(running.port)))
        TextButton(onClick = { open(Page.ACCESS) }) { Text(stringResource(R.string.reach_change)) }
    }
}

/** One need, as a card: what it is, in a line or two, and the one button that deals with it. */
@Composable
private fun NeedCard(need: Needs.Need, model: AppModel, actions: AppActions, open: (Page) -> Unit) {
    val trouble = need.ordinal <= Needs.Need.PASSAGE_OVERLAY.ordinal
    val n = needText(need, model)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (trouble) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier.padding(top = 6.dp, end = 12.dp).size(10.dp).background(
                    if (trouble) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    CircleShape,
                ),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                Text(n.title, style = MaterialTheme.typography.titleSmall)
                n.text?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { needAction(need, model, actions, open) }) { Text(n.button) }
                    if (need == Needs.Need.SERVER_UPDATE) {
                        TextButton(onClick = { open(Page.VERSIONS) }) { Text(stringResource(R.string.dock_app_whats_new)) }
                    }
                }
            }
        }
    }
}

/** A need's words: its title, a line, its button. */
private class NeedText(val title: String, val text: String?, val button: String)

@Composable
private fun needText(need: Needs.Need, model: AppModel): NeedText = when (need) {
    Needs.Need.SERVER_FAILED -> NeedText(
        stringResource(R.string.need_failed),
        (model.server as? ServerState.Failed)?.reason,
        stringResource(R.string.need_failed_go),
    )
    Needs.Need.NO_SERVER -> NeedText(stringResource(R.string.need_no_server), stringResource(R.string.need_no_server_text), stringResource(R.string.need_no_server_go))
    Needs.Need.PERMISSIONS -> NeedText(stringResource(R.string.need_permissions), stringResource(R.string.need_permissions_text), stringResource(R.string.perm_allow))
    Needs.Need.GPS_RESTART -> NeedText(stringResource(R.string.need_gps), stringResource(R.string.need_gps_text), stringResource(R.string.dock_gps_restart))
    Needs.Need.USB_PERMISSION -> NeedText(
        stringResource(R.string.need_usb),
        model.devices.firstOrNull { it.kind == BridgeProtocol.USB && it.state == BridgeProtocol.Device.NEEDS_PERMISSION }?.let { "${it.name} (${it.id})" },
        stringResource(R.string.dock_device_allow),
    )
    Needs.Need.CARD_MISSING -> NeedText(
        stringResource(R.string.need_card),
        stringResource(R.string.need_card_text, model.storage?.chosenName ?: model.storage?.chosen.orEmpty()),
        stringResource(R.string.need_open),
    )
    Needs.Need.MAIN_SERVER -> NeedText(
        stringResource(R.string.dock_satellite_title),
        when (val s = model.satellite) {
            is SatelliteJob.State.Ended -> stringResource(R.string.main_summary_ended, s.name)
            else -> stringResource(R.string.dock_satellite_unauthorized)
        },
        stringResource(R.string.need_open),
    )
    Needs.Need.MISSING_BUILDS -> NeedText(
        stringResource(R.string.need_missing),
        pluralStringResource(R.plurals.missing_summary, model.missingBuilds.size, model.missingBuilds.size),
        stringResource(R.string.need_missing_go),
    )
    Needs.Need.BATTERY -> NeedText(stringResource(R.string.need_battery), stringResource(R.string.perm_battery_why), stringResource(R.string.perm_battery_open))
    Needs.Need.PASSAGE_OVERLAY -> NeedText(
        stringResource(R.string.need_overlay),
        stringResource(R.string.dock_helm_overlay_why),
        stringResource(R.string.dock_helm_overlay_allow),
    )
    Needs.Need.SERVER_UPDATE -> NeedText(
        stringResource(R.string.need_update, serverOffer(model)?.text.orEmpty()),
        model.slots?.liveVersion?.let { stringResource(R.string.need_update_text, it) },
        stringResource(R.string.need_install),
    )
    Needs.Need.APP_UPDATE -> NeedText(
        stringResource(R.string.need_app_update, (model.app as? AppUpdater.State.Available)?.release?.versionName.orEmpty()),
        stringResource(R.string.dock_app_installed, BuildConfig.VERSION_NAME),
        stringResource(R.string.need_install),
    )
    Needs.Need.INBOX -> {
        val waiting = model.storage?.inbox
        NeedText(
            stringResource(R.string.need_inbox),
            waiting?.let { pluralStringResource(R.plurals.dock_inbox_waiting, it.files, it.files, Formatter.formatShortFileSize(LocalContext.current, it.bytes)) },
            stringResource(R.string.dock_inbox_take),
        )
    }
}

/** What a need's button does: the thing itself where one tap can do it, else the page that can. */
private fun needAction(need: Needs.Need, model: AppModel, actions: AppActions, open: (Page) -> Unit) {
    when (need) {
        Needs.Need.SERVER_FAILED -> open(Page.LOG)
        Needs.Need.NO_SERVER -> open(Page.VERSIONS)
        Needs.Need.PERMISSIONS -> actions.askPermissions()
        Needs.Need.GPS_RESTART -> actions.restartWithLocation()
        Needs.Need.USB_PERMISSION -> model.devices
            .firstOrNull { it.kind == BridgeProtocol.USB && it.state == BridgeProtocol.Device.NEEDS_PERMISSION }
            ?.let { actions.allowDevice(it.address) }
        Needs.Need.CARD_MISSING -> open(Page.DATA)
        Needs.Need.MAIN_SERVER -> open(Page.MAIN_SERVER)
        Needs.Need.MISSING_BUILDS -> open(Page.MISSING)
        Needs.Need.BATTERY -> actions.openBatterySettings()
        Needs.Need.PASSAGE_OVERLAY -> actions.openOverlaySettings()
        Needs.Need.SERVER_UPDATE -> serverOffer(model)?.let { actions.install(InstallManager.Source.Registry(it.text)) }
        Needs.Need.APP_UPDATE -> actions.updateApp()
        Needs.Need.INBOX -> actions.takeInbox()
    }
}

/**
 * The webapps installed (docs/PLAN.md §8.3) as tiles: a tap opens one in the browser, a stopped
 * server started first; with a launcher that takes them, each can be pinned to the home screen,
 * where one tap does the same ([fi.pocketsk.WebappShortcuts]).
 */
@Composable
private fun Webapps(model: AppModel, actions: AppActions) {
    val webapps = model.webapps ?: return
    Text(stringResource(R.string.dock_webapps_title), style = MaterialTheme.typography.titleMedium)
    if (webapps.isEmpty()) {
        Note(stringResource(R.string.dock_webapps_none))
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (w in webapps) {
            Card(
                onClick = { actions.openWebapp(w.name) },
                modifier = Modifier.width(TILE_WIDTH.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                    ) {
                        Text(
                            // A mark, not words: the first letter, upper case by Unicode alone.
                            w.title.trim().take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Text(w.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    if (model.canPin) {
                        TextButton(onClick = { actions.pinWebapp(w.name) }, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                            Text(stringResource(R.string.dock_webapps_pin))
                        }
                    }
                }
            }
        }
    }
}

/**
 * At the helm: the red night filter on or off and the power profile, the two things changed
 * under way, without opening a page; the rest of them on the Phone tab.
 */
@Composable
private fun HelmStrip(model: AppModel, actions: AppActions, open: (Page) -> Unit) {
    Text(stringResource(R.string.helm_strip_title), style = MaterialTheme.typography.titleMedium)
    Block {
        if (model.nightAllowed) {
            SwitchRow(stringResource(R.string.dock_helm_filter), model.night.filter) { actions.setNight(model.night.copy(filter = it)) }
        } else {
            Note(stringResource(R.string.dock_helm_overlay_why))
            OutlinedButton(onClick = actions::openOverlaySettings) { Text(stringResource(R.string.dock_helm_overlay_allow)) }
        }
        Text(stringResource(R.string.dock_power_title), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in PowerProfile.entries) {
                FilterChip(selected = model.power == p, onClick = { if (model.power != p) actions.setPower(p) }, label = { Text(stringResource(powerName(p))) })
            }
        }
        TextButton(onClick = { open(Page.HELM) }) { Text(stringResource(R.string.helm_strip_more)) }
    }
}

private const val RING_SIZE = 200
private const val RING_STROKE = 12
private const val TILE_WIDTH = 150

/** One turn of the ring's arc while data comes in, and while none does. */
private const val TURN_FED_MS = 2_400
private const val TURN_IDLE_MS = 9_000
