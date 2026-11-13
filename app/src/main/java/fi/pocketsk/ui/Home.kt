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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import fi.pocketsk.server.ServerState
import fi.pocketsk.server.ServerUsage
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Home (docs/PLAN.md §8.1, §16 16): the boat's alarms while there are any; the server as a ring,
 * coloured by its state and turning while data comes in, with the one thing to do with it now
 * (start it, or open its admin UI); and where tablets reach it. What needs the owner, the webapps
 * and the helm's quick controls are on the Server tab ([NeedsSection]), as the owner asked.
 */
@Composable
internal fun HomeTab(model: AppModel, actions: AppActions, open: (Page) -> Unit) {
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

private const val RING_SIZE = 200
private const val RING_STROKE = 12

/** One turn of the ring's arc while data comes in, and while none does. */
private const val TURN_FED_MS = 2_400
private const val TURN_IDLE_MS = 9_000
