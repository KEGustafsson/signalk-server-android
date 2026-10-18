package fi.pocketsk.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fi.pocketsk.install.InstallHistory
import fi.pocketsk.install.InstallManager
import fi.pocketsk.install.ServerVersion
import fi.pocketsk.server.ServerState
import fi.pocketsk.server.ServerStatus
import java.io.File

/**
 * What the Dock and the setup wizard show, as Compose state. The activity fills it from the
 * process-wide [ServerStatus] and [fi.pocketsk.install.InstallJob] and from reads it makes on a
 * worker thread, and writes it on the main thread only; the screens read it and call [AppActions].
 */
internal class AppModel {
    var server by mutableStateOf(ServerStatus.state)
    var log by mutableStateOf("")
    var install by mutableStateOf<InstallManager.State?>(null)
    var installing by mutableStateOf(false)

    /** Null until first read: the slots, their versions and the history, read off the main thread. */
    var slots by mutableStateOf<SlotsView?>(null)
    var available by mutableStateOf<Available>(Available.Unknown)
    var lanAddress by mutableStateOf<String?>(null)
    var permissions by mutableStateOf(PermissionsView())

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

/** signalk-server's published versions, as far as asked. */
internal sealed interface Available {
    data object Unknown : Available
    data object Loading : Available
    data class Loaded(val versions: List<ServerVersion>) : Available
    data class Failed(val reason: String) : Available
}

/**
 * What is granted. Null where it does not apply on this phone: notifications below Android 13,
 * local network access below Android 17.
 */
internal data class PermissionsView(
    val notifications: Boolean? = null,
    val localNetwork: Boolean? = null,
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
    fun install(source: InstallManager.Source)
    fun pickTarball()
    fun rollBack()
    fun askPermissions()
    fun openBatterySettings()
    fun setAutostart(on: Boolean)
    fun finishSetup(startServer: Boolean)
    fun openRuntimeChecks()
}
