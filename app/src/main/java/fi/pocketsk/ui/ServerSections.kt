package fi.pocketsk.ui

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fi.pocketsk.BuildConfig
import fi.pocketsk.R
import fi.pocketsk.bridge.BridgeProtocol
import fi.pocketsk.install.InstallManager
import fi.pocketsk.power.PowerProfile
import fi.pocketsk.satellite.SatelliteJob
import fi.pocketsk.server.ServerState
import fi.pocketsk.update.AppUpdater

/*
 * The Server tab's sections above and below its rows (docs/PLAN.md §16 16): what needs the owner,
 * the webapps and the helm's quick controls, moved there from Home at the owner's asking
 * (2026-11-13), so Home holds the server's state and its one action alone.
 */

/**
 * What needs the owner now ([Needs]), each a card with its one button, at the top of the Server
 * tab; "all is well" while the server runs and nothing does.
 */
@Composable
internal fun NeedsSection(model: AppModel, actions: AppActions, needs: List<Needs.Need>, open: (Page) -> Unit) {
    if (needs.isNotEmpty()) {
        Text(stringResource(R.string.needs_title), style = MaterialTheme.typography.titleMedium)
        for (need in needs) NeedCard(need, model, actions, open)
    } else if (model.server is ServerState.Running) {
        Note(stringResource(R.string.needs_none))
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
        Needs.Need.SERVER_UPDATE -> serverOffer(model)?.let {
            // The page shows the install's progress, its last lines and a failure, which this
            // card, gone once the install starts, cannot.
            open(Page.VERSIONS)
            actions.install(InstallManager.Source.Registry(it.text))
        }
        Needs.Need.APP_UPDATE -> {
            // The page shows the download, the installer's answer and a failure, which this card,
            // gone once the update starts, cannot.
            open(Page.APP_UPDATE)
            actions.updateApp()
        }
        Needs.Need.INBOX -> actions.takeInbox()
    }
}

/**
 * At the helm: the red night filter on or off and the power profile, the two things changed
 * under way, without opening a page; the rest of them on the Phone tab.
 */
@Composable
internal fun HelmStrip(model: AppModel, actions: AppActions, open: (Page) -> Unit) {
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

/**
 * The webapps installed (docs/PLAN.md §8.3) as tiles: a tap opens one in the browser, a stopped
 * server started first; with a launcher that takes them, each can be pinned to the home screen,
 * where one tap does the same ([fi.pocketsk.WebappShortcuts]).
 */
@Composable
internal fun WebappTiles(model: AppModel, actions: AppActions) {
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

private const val TILE_WIDTH = 150
