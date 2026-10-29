package fi.pocketsk.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fi.pocketsk.bridge.AlarmBook
import fi.pocketsk.bridge.BridgeProtocol
import fi.pocketsk.install.InstallHistory
import fi.pocketsk.install.InstallManager
import fi.pocketsk.install.ReleaseNotes
import fi.pocketsk.install.ServerVersion
import fi.pocketsk.night.NightSettings
import fi.pocketsk.power.PowerProfile
import fi.pocketsk.server.BackupJob
import fi.pocketsk.server.DataVolumes
import fi.pocketsk.server.Inbox
import fi.pocketsk.server.LogFilter
import fi.pocketsk.server.ServerState
import fi.pocketsk.server.ServerStatus
import fi.pocketsk.server.ServerUsage
import fi.pocketsk.signalk.Webapps
import fi.pocketsk.update.AppUpdater
import java.io.File

/**
 * What the Dock and the setup wizard show, as Compose state. The activity fills it from the
 * process-wide [ServerStatus] and [fi.pocketsk.install.InstallJob] and from reads it makes on a
 * worker thread, and writes it on the main thread only; the screens read it and call [AppActions].
 */
internal class AppModel {
    var server by mutableStateOf(ServerStatus.state)
    /** The Logs card's lines, the newest of those [logKind] and [logQuery] choose. */
    var log by mutableStateOf("")
    var logKind by mutableStateOf(LogFilter.Kind.ALL)
    var logQuery by mutableStateOf("")
    /** A diagnostics bundle is being written. */
    var sharing by mutableStateOf(false)
    var install by mutableStateOf<InstallManager.State?>(null)
    var installing by mutableStateOf(false)

    /** Null until first read: the slots, their versions and the history, read off the main thread. */
    var slots by mutableStateOf<SlotsView?>(null)
    var available by mutableStateOf<Available>(Available.Unknown)
    /** signalk-server's release notes, read from GitHub when the Updates card opens them. */
    var notes by mutableStateOf<Notes>(Notes.Unknown)
    /** The app's own update; null where a store that keeps the app current installed it. */
    var app by mutableStateOf<AppUpdater.State?>(null)
    /** The Dock looks for a newer build of the app once a day. */
    var appChecks by mutableStateOf(true)
    /** Android lets the app ask to install apps, which its own update needs. */
    var appInstallsAllowed by mutableStateOf(false)
    var lanAddress by mutableStateOf<String?>(null)
    var permissions by mutableStateOf(PermissionsView())
    var devices by mutableStateOf<List<BridgeProtocol.Device>>(emptyList())
    var alarms by mutableStateOf(AlarmBook.View.NONE)
    /** [ServerStatus.locationHeld]: false is a server that runs without the phone's GNSS. */
    var locationHeld by mutableStateOf<Boolean?>(null)
    /** The running server's uptime, CPU and memory, looked at while the Dock is in front; null while none runs. */
    var usage by mutableStateOf<ServerUsage.Usage?>(null)
    /** [ServerStatus.deltaRate]: what the server takes in, deltas a second; null without the bridge's plugin. */
    var deltaRate by mutableStateOf<Double?>(null)

    /** Where the server's data is and where it could go; null until first read, off the main thread. */
    var storage by mutableStateOf<StorageView?>(null)

    /** The webapps installed, by title; null until first read, off the main thread. */
    var webapps by mutableStateOf<List<Webapps.Webapp>?>(null)
    /** The home screen takes pinned shortcuts. */
    var canPin by mutableStateOf(false)

    /** The helm screen's settings, and whether the app may draw over other apps, which they need. */
    var night by mutableStateOf(NightSettings())
    var nightAllowed by mutableStateOf(false)

    /** The power profile chosen ([fi.pocketsk.power.PowerRules]). */
    var power by mutableStateOf(PowerProfile.HARBOR)

    /** The backup or restore running or last finished ([fi.pocketsk.server.BackupJob]); null before the first. */
    var backup by mutableStateOf<BackupJob.State?>(null)

    /** A sideloaded `.tgz`, copied into the app's cache, once one has been picked. */
    var tarball by mutableStateOf<File?>(null)
    var tarballName by mutableStateOf<String?>(null)
}

/** The two slots as the Updates card shows them. */
internal data class SlotsView(
    val live: String?,
    val liveVersion: String?,
    val other: String,
    val otherVersion: String?,
    val history: InstallHistory,
)

/**
 * The Storage card: the card the data is on ([chosen], its UUID, and [chosenName]; both null for
 * the phone), the phone's free and total bytes, and the cards in the phone now.
 */
internal data class StorageView(
    val chosen: String?,
    val chosenName: String?,
    val phoneFree: Long,
    val phoneTotal: Long,
    val volumes: List<DataVolumes.Volume>,
    /** The files waiting in the USB inbox, and their bytes; null when the phone's shared storage is not there. */
    val inbox: Inbox.Waiting?,
)

/** signalk-server's published versions, as far as asked. */
internal sealed interface Available {
    data object Unknown : Available
    data object Loading : Available
    data class Loaded(val versions: List<ServerVersion>) : Available
    data class Failed(val reason: String) : Available
}

/** signalk-server's release notes, as far as asked. */
internal sealed interface Notes {
    data object Unknown : Notes
    data object Loading : Notes
    data class Loaded(val releases: List<ReleaseNotes.Release>) : Notes
    data class Failed(val reason: String) : Notes
}

/**
 * What is granted. Null where it does not apply on this phone: notifications below Android 13,
 * local network access below Android 17, full-screen alarms below Android 14. [location] is precise location, for the bridge's GNSS:
 * optional, since the server runs without it.
 */
internal data class PermissionsView(
    val notifications: Boolean? = null,
    val localNetwork: Boolean? = null,
    val location: Boolean = false,
    /**
     * Nearby devices on Android 12+: talking to paired Bluetooth devices (BLUETOOTH_CONNECT), for
     * the serial devices, and scanning for Bluetooth LE ones (BLUETOOTH_SCAN).
     */
    val bluetooth: Boolean = true,
    /** The alarm's full-screen intent ([fi.pocketsk.bridge.PhoneAlarm]), which wakes a locked phone. */
    val fullScreen: Boolean? = null,
    val batteryUnrestricted: Boolean = false,
    val autostart: Boolean = false,
)

/** What the screens can ask for; the activity does it. */
internal interface AppActions {
    fun startServer()
    fun stopServer()
    fun restartServer()
    fun openAdmin()
    fun loadVersions(refresh: Boolean)
    /** signalk-server's release notes for the Updates card, from GitHub or the day's copy. */
    fun loadNotes()
    /** signalk-server's releases on GitHub, in the browser. */
    fun openReleases()
    /**
     * Downloads the app's newer build and hands it to Android's installer; first, while Android
     * does not let the app install apps, the setting that allows it.
     */
    fun updateApp()
    /** The app's own release on offer, on GitHub in the browser. */
    fun openAppRelease()
    /** Turns the Dock's daily look for a newer build of the app on or off. */
    fun setAppChecks(on: Boolean)
    fun install(source: InstallManager.Source)
    fun pickTarball()
    fun rollBack()
    fun askPermissions()
    fun askLocation()
    fun openBatterySettings()
    fun setAutostart(on: Boolean)
    fun finishSetup(startServer: Boolean)
    fun openRuntimeChecks()
    fun levelAttitude()
    /** Android's dialog that allows the app the USB device at [address]. */
    fun allowDevice(address: String)
    /** Reads the paired Bluetooth device at [address], or stops reading it. */
    fun chooseBluetooth(address: String, chosen: Boolean)
    /** Asks for the Bluetooth permission the serial devices need. */
    fun askBluetooth()
    /** Silence (or Acknowledge) for the boat's alarm [id]. */
    fun silenceAlarm(id: String)
    /** The system's page that allows the app full-screen alarms (Android 14 and later). */
    fun openFullScreenSettings()
    /** Stops the server and starts it again from this screen, so its service may take location. */
    fun restartWithLocation()
    /** Moves the server's data to [volume], or to the phone when null; the server restarts to do it. */
    fun chooseStorage(volume: DataVolumes.Volume?)

    /** Chooses the power profile: the phone serves the plugin and dims the screen as it says. */
    fun setPower(profile: PowerProfile)

    /** Saves a backup encrypted with [passphrase] where the owner picks; [passphrase] is wiped once used. */
    fun saveBackup(passphrase: CharArray)

    /** Restores a backup the owner picks, opened with [passphrase]; [passphrase] is wiped once used. */
    fun restoreBackup(passphrase: CharArray)

    /** Takes what waits in the USB inbox into the server's data folders now. */
    fun takeInbox()

    /** Shows the log's lines of [kind] that hold every word of [query]. */
    fun filterLog(kind: LogFilter.Kind, query: String)

    /** Writes a diagnostics bundle and offers it to the share sheet. */
    fun shareDiagnostics()

    /** The webapp [name] in the browser, the server started first when it is stopped. */
    fun openWebapp(name: String)

    /** Asks the home screen to pin a shortcut that opens the webapp [name]. */
    fun pinWebapp(name: String)

    /** Keeps the helm screen's settings and puts them on the screen at once. */
    fun setNight(settings: NightSettings)

    /** The system's page that allows the app to draw over other apps, for the night filter. */
    fun openOverlaySettings()
}
