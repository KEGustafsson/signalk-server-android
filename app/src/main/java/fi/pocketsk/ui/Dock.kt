package fi.pocketsk.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fi.pocketsk.BuildConfig
import fi.pocketsk.R
import fi.pocketsk.bridge.BridgeProtocol
import fi.pocketsk.install.UpdateOffer
import fi.pocketsk.power.PowerProfile
import fi.pocketsk.satellite.SatelliteJob
import fi.pocketsk.server.BackupJob
import fi.pocketsk.server.ServerState
import fi.pocketsk.update.AppUpdater

/**
 * The Dock's four places, in the bottom bar (docs/PLAN.md §8.1, §16 16): Home, what the server is
 * doing and what needs the owner; Devices, what feeds it; Server, its versions, reach, data,
 * backups, log and reset; Phone, what the phone does for it.
 */
internal enum class Tab(@StringRes val label: Int, @DrawableRes val icon: Int) {
    HOME(R.string.tab_home, R.drawable.ic_tab_home),
    DEVICES(R.string.tab_devices, R.drawable.ic_tab_devices),
    SERVER(R.string.tab_server, R.drawable.ic_tab_server),
    PHONE(R.string.tab_phone, R.drawable.ic_tab_phone),
}

/** A page of its own, one subject each, opened from a row of [tab] or from a card on Home. */
internal enum class Page(val tab: Tab) {
    SENSORS(Tab.DEVICES),
    USB(Tab.DEVICES),
    BLUETOOTH(Tab.DEVICES),
    MAIN_SERVER(Tab.DEVICES),
    VERSIONS(Tab.SERVER),
    ACCESS(Tab.SERVER),
    DATA(Tab.SERVER),
    BACKUP(Tab.SERVER),
    LOG(Tab.SERVER),
    MISSING(Tab.SERVER),
    RESET(Tab.SERVER),
    HELM(Tab.PHONE),
    POWER(Tab.PHONE),
    PERMISSIONS(Tab.PHONE),
    APP_UPDATE(Tab.PHONE),
}

/**
 * The Dock (docs/PLAN.md §8.1): the bottom bar's four tabs, each a short list, each row opening a
 * page with one subject on it. Back goes from a page to its tab, and from a tab to Home. A tab
 * with something in it that wants the owner carries a dot, the same things Home lists under
 * "Needs you".
 */
@Composable
internal fun DockScreen(model: AppModel, actions: AppActions) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    val open: (Page) -> Unit = {
        tab = it.tab
        page = it
    }
    BackHandler(enabled = page != null || tab != Tab.HOME) {
        if (page != null) page = null else tab = Tab.HOME
    }
    // npm's versions (a day's copy), so Home can say a newer server is out without a check by hand.
    LaunchedEffect(Unit) { actions.loadVersions(refresh = false) }
    val needs = Needs.list(needsInput(model))
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                for (t in Tab.entries) {
                    val dot = t != Tab.HOME && needs.any { needTab(it) == t }
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = {
                            tab = t
                            page = null
                        },
                        icon = {
                            BadgedBox(badge = { if (dot) Badge() }) {
                                Icon(painterResource(t.icon), contentDescription = null)
                            }
                        },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = Place(tab, page),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "dock",
        ) { place ->
            Column(
                modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val back = { page = null }
                when (val p = place.page) {
                    null -> when (place.tab) {
                        Tab.HOME -> HomeTab(model, actions, needs, open)
                        Tab.DEVICES -> DevicesTab(model, open)
                        Tab.SERVER -> ServerTab(model, open)
                        Tab.PHONE -> PhoneTab(model, open)
                    }
                    Page.SENSORS -> SensorsPage(model, actions, back)
                    Page.USB -> UsbPage(model, actions, back)
                    Page.BLUETOOTH -> BluetoothPage(model, actions, back)
                    Page.MAIN_SERVER -> MainServerPage(model, actions, back)
                    Page.VERSIONS -> VersionsPage(model, actions, back)
                    Page.ACCESS -> AccessPage(model, actions, back)
                    Page.DATA -> DataPage(model, actions, back)
                    Page.BACKUP -> BackupPage(model, actions, back)
                    Page.LOG -> LogPage(model, actions, back)
                    Page.MISSING -> MissingPage(model, actions, back)
                    Page.RESET -> ResetPage(model, actions, back)
                    Page.HELM -> HelmPage(model, actions, back)
                    Page.POWER -> PowerPage(model, actions, back)
                    Page.PERMISSIONS -> PermissionsPage(model, actions, back)
                    Page.APP_UPDATE -> AppUpdatePage(model, actions, back)
                }
            }
        }
    }
}

/** Where the Dock is: a tab, and the page open on it if any. */
private data class Place(val tab: Tab, val page: Page?)

/** The tab a need is dealt with on, which carries its dot in the bottom bar. */
private fun needTab(need: Needs.Need): Tab = when (need) {
    Needs.Need.GPS_RESTART, Needs.Need.USB_PERMISSION, Needs.Need.MAIN_SERVER -> Tab.DEVICES
    Needs.Need.SERVER_FAILED, Needs.Need.NO_SERVER, Needs.Need.CARD_MISSING, Needs.Need.MISSING_BUILDS,
    Needs.Need.SERVER_UPDATE, Needs.Need.INBOX -> Tab.SERVER
    Needs.Need.PERMISSIONS, Needs.Need.BATTERY, Needs.Need.PASSAGE_OVERLAY, Needs.Need.APP_UPDATE -> Tab.PHONE
}

/** The facts [Needs] decides from, as [AppModel] holds them now. */
internal fun needsInput(model: AppModel): Needs.Input {
    val slots = model.slots
    val storage = model.storage
    val satellite = model.satellite
    val p = model.permissions
    return Needs.Input(
        serverFailed = model.server is ServerState.Failed,
        // Unknown until the slots are read: nothing is asked for meanwhile.
        serverInstalled = slots == null || slots.liveVersion != null,
        idle = !model.busy,
        permissionsMissing = p.notifications == false || p.localNetwork == false,
        gpsRestart = p.location && model.locationHeld == false && model.server.isActive,
        usbWaiting = model.devices.any { it.kind == BridgeProtocol.USB && it.state == BridgeProtocol.Device.NEEDS_PERMISSION },
        cardMissing = storage?.chosen != null && storage.volumes.none { it.mounted && it.uuid == storage.chosen },
        mainServerTrouble = satellite is SatelliteJob.State.Ended ||
            (satellite as? SatelliteJob.State.Following)?.link == BridgeProtocol.SatelliteLink.UNAUTHORIZED,
        missingBuilds = model.missingBuilds.isNotEmpty(),
        batteryRestricted = !p.batteryUnrestricted,
        passageNeedsOverlay = model.power == PowerProfile.PASSAGE && !model.nightAllowed,
        serverUpdate = serverOffer(model) != null,
        appUpdate = model.app is AppUpdater.State.Available,
        inboxFiles = storage?.inbox?.files ?: 0,
    )
}

/** The newer release of the server on offer, if npm has been asked and has one. */
internal fun serverOffer(model: AppModel) = model.slots?.let { slots ->
    (model.available as? Available.Loaded)?.let { UpdateOffer.pick(it.versions, slots.liveVersion, slots.history) }
}

/** Devices: what feeds the server, a row each with how it stands. */
@Composable
private fun DevicesTab(model: AppModel, open: (Page) -> Unit) {
    TabHeader(stringResource(R.string.tab_devices))
    Note(stringResource(R.string.devices_intro))
    val usb = model.devices.filter { it.kind == BridgeProtocol.USB }
    val bluetooth = model.devices.filter { it.kind == BridgeProtocol.BLUETOOTH }
    val p = model.permissions
    RowGroup {
        val gpsRestart = p.location && model.locationHeld == false && model.server.isActive
        NavRow(
            stringResource(R.string.sensors_title),
            stringResource(
                when {
                    gpsRestart -> R.string.sensors_summary_no_gps_now
                    !p.location -> R.string.sensors_summary_no_gps
                    else -> R.string.sensors_summary
                },
            ),
            onClick = { open(Page.SENSORS) },
            alert = gpsRestart,
        )
        RowDivider()
        val waiting = usb.firstOrNull { it.state == BridgeProtocol.Device.NEEDS_PERMISSION }
        NavRow(
            stringResource(R.string.usb_title),
            when {
                waiting != null -> stringResource(R.string.usb_summary_waiting, waiting.name)
                !model.server.isActive -> stringResource(R.string.devices_summary_stopped)
                usb.isEmpty() -> stringResource(R.string.usb_summary_none)
                else -> pluralStringResource(R.plurals.usb_summary, usb.size, usb.size)
            },
            onClick = { open(Page.USB) },
            alert = waiting != null,
        )
        RowDivider()
        val open = bluetooth.count { it.state == BridgeProtocol.Device.OPEN }
        NavRow(
            stringResource(R.string.bluetooth_title),
            when {
                !p.bluetooth -> stringResource(R.string.bluetooth_summary_not_allowed)
                !model.server.isActive -> stringResource(R.string.devices_summary_stopped)
                bluetooth.isEmpty() -> stringResource(R.string.bluetooth_summary_none)
                else -> pluralStringResource(R.plurals.bluetooth_summary, bluetooth.size, open, bluetooth.size)
            },
            onClick = { open(Page.BLUETOOTH) },
        )
        RowDivider()
        val s = model.satellite
        NavRow(
            stringResource(R.string.dock_satellite_title),
            when (s) {
                is SatelliteJob.State.Following -> stringResource(R.string.main_summary_following, s.name)
                is SatelliteJob.State.Asking -> stringResource(R.string.main_summary_asking, s.name)
                is SatelliteJob.State.Ended -> stringResource(R.string.main_summary_ended, s.name)
                SatelliteJob.State.Off ->
                    if (model.satelliteFound.isEmpty()) stringResource(R.string.main_summary_off)
                    else pluralStringResource(R.plurals.main_summary_found, model.satelliteFound.size, model.satelliteFound.size)
            },
            onClick = { open(Page.MAIN_SERVER) },
            alert = s is SatelliteJob.State.Ended ||
                (s as? SatelliteJob.State.Following)?.link == BridgeProtocol.SatelliteLink.UNAUTHORIZED,
        )
    }
}

/** Server: its version, who reaches it, where its data is, its backups, its log; the reset apart, last. */
@Composable
private fun ServerTab(model: AppModel, open: (Page) -> Unit) {
    TabHeader(stringResource(R.string.tab_server))
    val slots = model.slots
    val offer = serverOffer(model)
    RowGroup {
        NavRow(
            stringResource(R.string.versions_title),
            when {
                model.installing -> installStateText(model.install)
                slots == null -> null
                slots.liveVersion == null -> stringResource(R.string.dock_nothing_live)
                offer != null -> stringResource(R.string.versions_summary_offer, slots.liveVersion, offer.text)
                model.available is Available.Loaded -> stringResource(R.string.versions_summary_current, slots.liveVersion)
                else -> slots.liveVersion
            },
            onClick = { open(Page.VERSIONS) },
            alert = slots?.liveVersion == null && slots != null,
            accent = offer != null,
        )
        RowDivider()
        NavRow(
            stringResource(R.string.access_title),
            stringResource(if (model.lanAccess) R.string.access_lan else R.string.access_phone),
            onClick = { open(Page.ACCESS) },
        )
        RowDivider()
        val storage = model.storage
        val missing = storage?.chosen != null && storage.volumes.none { it.mounted && it.uuid == storage.chosen }
        NavRow(
            stringResource(R.string.data_title),
            when {
                storage == null -> null
                missing -> stringResource(R.string.dock_storage_missing, storage.chosenName ?: storage.chosen.orEmpty())
                storage.chosen == null -> stringResource(R.string.data_summary, stringResource(R.string.dock_storage_phone), freeText(storage.phoneFree))
                else -> storage.volumes.firstOrNull { it.uuid == storage.chosen }?.let {
                    stringResource(R.string.data_summary, it.name, freeText(it.free))
                }
            },
            onClick = { open(Page.DATA) },
            alert = missing,
        )
        RowDivider()
        NavRow(stringResource(R.string.backup_title), backupSummary(model.backup), onClick = { open(Page.BACKUP) })
        RowDivider()
        NavRow(
            stringResource(R.string.dock_log_title),
            stringResource(if (model.server is ServerState.Failed) R.string.log_summary_failed else R.string.log_summary),
            onClick = { open(Page.LOG) },
            alert = model.server is ServerState.Failed,
        )
        if (model.missingBuilds.isNotEmpty()) {
            RowDivider()
            NavRow(
                stringResource(R.string.dock_missing_title),
                pluralStringResource(R.plurals.missing_summary, model.missingBuilds.size, model.missingBuilds.size),
                onClick = { open(Page.MISSING) },
                alert = true,
            )
        }
    }
    RowGroup {
        NavRow(stringResource(R.string.reset_title), stringResource(R.string.reset_summary), onClick = { open(Page.RESET) }, danger = true)
    }
}

/** What the last backup or restore came to, in the Server tab's row. */
@Composable
private fun backupSummary(state: BackupJob.State?): String = when (state) {
    is BackupJob.State.Working -> stringResource(if (state.restore) R.string.dock_backup_restoring else R.string.dock_backup_saving)
    is BackupJob.State.Saved -> stringResource(R.string.backup_summary_saved)
    is BackupJob.State.Restored -> stringResource(R.string.backup_summary_restored)
    else -> stringResource(R.string.backup_summary)
}

/** Phone: what the phone does for the server, the helm screen and power first, its grants and its own update. */
@Composable
private fun PhoneTab(model: AppModel, open: (Page) -> Unit) {
    TabHeader(stringResource(R.string.tab_phone))
    val night = model.night
    val p = model.permissions
    val toAllow = Needs.permissionsToAllow(p.notifications, p.localNetwork, p.location, p.fullScreen, p.batteryUnrestricted)
    RowGroup {
        NavRow(
            stringResource(R.string.dock_helm_title),
            when {
                !model.nightAllowed -> stringResource(R.string.helm_summary_not_allowed)
                else -> listOfNotNull(
                    if (night.filter) stringResource(R.string.helm_summary_filter, night.strength) else null,
                    if (night.dim) stringResource(R.string.helm_summary_dim, night.brightness) else null,
                    if (night.keepOn) stringResource(R.string.helm_summary_on) else null,
                ).joinToString(" · ").ifEmpty { stringResource(R.string.helm_summary_off) }
            },
            onClick = { open(Page.HELM) },
        )
        RowDivider()
        NavRow(stringResource(R.string.dock_power_title), stringResource(powerName(model.power)), onClick = { open(Page.POWER) })
        RowDivider()
        NavRow(
            stringResource(R.string.permissions_title),
            if (toAllow == 0) stringResource(R.string.permissions_summary_all) else pluralStringResource(R.plurals.permissions_summary, toAllow, toAllow),
            onClick = { open(Page.PERMISSIONS) },
            alert = p.notifications == false || p.localNetwork == false,
        )
        model.app?.let { app ->
            RowDivider()
            val available = app as? AppUpdater.State.Available
            NavRow(
                stringResource(R.string.dock_app_update_title),
                available?.let { stringResource(R.string.app_summary_offer, it.release.versionName) }
                    ?: stringResource(R.string.dock_app_installed, BuildConfig.VERSION_NAME),
                onClick = { open(Page.APP_UPDATE) },
                accent = available != null,
            )
        }
    }
    Note(stringResource(R.string.dock_app_version, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA))
}

/** A profile's name, as the Power page, its row and Home's helm strip say it. */
@StringRes
internal fun powerName(p: PowerProfile): Int = when (p) {
    PowerProfile.HARBOR -> R.string.dock_power_harbor
    PowerProfile.PASSAGE -> R.string.dock_power_passage
    PowerProfile.SAVER -> R.string.dock_power_saver
}
