package fi.pocketsk.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * (start it, or open its admin UI); and where tablets reach it. Nothing else, as the owner asked:
 * what needs them is a tab's dot and a marked row, the webapps are on the Server tab and the helm
 * screen and power on the Phone tab.
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
    AddressCard(model, open)
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
 * Where the boat reaches the server: the address a tablet or plotter types, large, on one line,
 * with a button that copies it, the phone's own address under it; this phone's address in its
 * place when only the phone reaches the server, or no network is there, with a line saying so;
 * and, on Home, the row that changes who may reach it. While the server is stopped the address
 * is shown dimmed and without a port, since the port is the server's own setting, known only
 * once it runs.
 */
@Composable
internal fun AddressCard(model: AppModel, open: ((Page) -> Unit)?) {
    val running = model.server as? ServerState.Running
    // The run's own reach, not the setting's: a change takes effect with the restart it asks for.
    val lanOn = running?.lan ?: model.lanAccess
    val lan = model.lanAddress?.takeIf { lanOn }
    if (running == null && lan == null) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(if (lan != null) R.drawable.ic_wifi else R.drawable.ic_tab_phone),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(if (lan != null) R.string.reach_lan_label else R.string.reach_phone_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            // The address a tablet types: the boat's network's, or this phone's own when only it
            // reaches the server; dimmed, without a port, while the server is stopped.
            val local = running?.port?.let { ServerActions.localUrl(it).removePrefix("http://") }
            val shown = when {
                lan != null -> running?.port?.let { "$lan:$it" } ?: lan
                else -> local
            }
            shown?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // One line, shrinking to fit a narrow phone or a large font rather than
                    // breaking inside the port or cutting it off.
                    val large = MaterialTheme.typography.headlineSmall
                    BasicText(
                        it,
                        style = large.copy(
                            fontWeight = FontWeight.Medium,
                            color = if (running != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        maxLines = 1,
                        softWrap = false,
                        autoSize = TextAutoSize.StepBased(minFontSize = ADDRESS_MIN_SP.sp, maxFontSize = large.fontSize),
                        modifier = Modifier.weight(1f),
                    )
                    if (running != null) CopyButton("http://$it")
                }
            }
            Text(
                stringResource(
                    when {
                        running == null -> R.string.reach_when_running
                        lan != null -> R.string.reach_type_it
                        running.lan -> R.string.reach_no_network
                        else -> R.string.reach_phone_only
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
            // The phone's own address under the boat's, unless it is the one shown already.
            if (lan != null && local != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, end = 8.dp)) {
                    Text(
                        stringResource(R.string.reach_phone_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(local, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        open?.let { go ->
            RowDivider()
            NavRow(
                stringResource(R.string.access_title),
                stringResource(if (model.lanAccess) R.string.access_lan else R.string.access_phone),
                onClick = { go(Page.ACCESS) },
            )
        }
    }
}

/**
 * Copies the address for pasting into a tablet's browser or a message. Android 13 and later show
 * their own confirmation of a copy; below that the app says it, as Android asks.
 */
@Composable
private fun CopyButton(url: String) {
    val context = LocalContext.current
    IconButton(onClick = {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(url, url))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.reach_copied, Toast.LENGTH_SHORT).show()
        }
    }) {
        Icon(painterResource(R.drawable.ic_copy), contentDescription = stringResource(R.string.reach_copy), tint = MaterialTheme.colorScheme.primary)
    }
}

private const val RING_SIZE = 200

/**
 * The smallest the address shrinks to on one line. In sp, so it grows with the phone's font
 * setting too: 10 keeps the longest address and its port on a 320 dp screen at twice the font.
 */
private const val ADDRESS_MIN_SP = 10
private const val RING_STROKE = 12

/** One turn of the ring's arc while data comes in, and while none does. */
private const val TURN_FED_MS = 2_400
private const val TURN_IDLE_MS = 9_000
