package fi.pocketsk

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import fi.pocketsk.install.InstallJob
import fi.pocketsk.install.InstallManager
import fi.pocketsk.install.Slots
import fi.pocketsk.net.transportThread
import fi.pocketsk.node.NodeRuntime
import fi.pocketsk.server.LanAddress
import fi.pocketsk.server.ServerPrefs
import fi.pocketsk.server.ServerState
import fi.pocketsk.server.ServerStatus
import fi.pocketsk.server.SignalKService
import fi.pocketsk.ui.AppActions
import fi.pocketsk.ui.AppModel
import fi.pocketsk.ui.Available
import fi.pocketsk.ui.DockScreen
import fi.pocketsk.ui.PermissionsView
import fi.pocketsk.ui.PocketTheme
import fi.pocketsk.ui.SetupScreen
import fi.pocketsk.ui.SlotsView
import java.io.File
import java.io.IOException

/**
 * The app's screen (docs/PLAN.md §5.3, §8.1): the setup wizard until it has been finished once,
 * the Dock after it. Everything it shows lives in the process ([ServerStatus], [InstallJob]) or
 * on disk, so a rotation or a reopened app picks it up where it was; this activity only fills
 * [AppModel] from those, off the main thread for anything that touches files or npm, and turns
 * the screens' [AppActions] into calls on the service and the installer.
 *
 * Opened with [ACTION_START] (the Quick Settings tile, when Android refused it a start of its
 * own), it starts the server as the Dock's Start would.
 */
class MainActivity : ComponentActivity() {
    private lateinit var runtime: NodeRuntime
    private lateinit var installs: InstallManager
    private lateinit var prefs: ServerPrefs
    private val model = AppModel()

    /** Null until the first read of the prefs and the slots says which screen comes first. */
    private var setupDone by mutableStateOf<Boolean?>(null)

    private val serverObserver = ServerStatus.Observer { state ->
        val wasActive = model.server.isActive
        model.server = state
        model.log = ServerStatus.log(this).tail(LOG_LINES)
        // A start, a rollback or an end changes what is live or where it is reached.
        if (state is ServerState.Starting || state.isActive != wasActive) refreshSlots()
        if (state is ServerState.Running) refreshAddress()
    }

    private val installObserver = InstallJob.Observer { state, running ->
        runOnUiThread {
            model.install = state
            model.installing = running
            if (!running) refreshSlots()
        }
    }

    private val askThenStart = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        ServerActions.noteRefused(this, granted)
        refreshPermissions()
        SignalKService.start(this)
    }

    private val askOnly = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        ServerActions.noteRefused(this, granted)
        refreshPermissions()
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) copyTarball(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        runtime = NodeRuntime(this)
        installs = InstallManager(this, runtime)
        prefs = ServerPrefs(this)
        setContent {
            PocketTheme {
                Scaffold { padding ->
                    val inner = Modifier.padding(padding)
                    when (setupDone) {
                        null -> Box(inner.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        false -> SetupScreen(model, actions, inner)
                        true -> DockScreen(model, actions, inner)
                    }
                }
            }
        }
        // A phone where a server was installed before the wizard existed (the runtime checks'
        // installer) has nothing to set up.
        transportThread("dock-first-read", onError = { runOnUiThread { setupDone = prefs.setupDone } }) {
            val done = prefs.setupDone || installs.liveSlot() != null
            runOnUiThread { setupDone = done }
        }
        ServerStatus.observe(serverObserver)
        InstallJob.observe(installObserver)
        refreshSlots()
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        // Settings opened from here (battery, an app permission) may have changed something.
        refreshPermissions()
        refreshAddress()
    }

    override fun onDestroy() {
        ServerStatus.detach(serverObserver)
        InstallJob.detach(installObserver)
        super.onDestroy()
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_START) startServer()
    }

    // ---- what the screens ask for ----------------------------------------------------------------

    /** The screens' [AppActions], kept off the activity's public face (the types are the app's own). */
    private val actions = object : AppActions {
        override fun startServer() = this@MainActivity.startServer()
        override fun stopServer() = this@MainActivity.stopServer()
        override fun restartServer() = this@MainActivity.restartServer()
        override fun openAdmin() = this@MainActivity.openAdmin()
        override fun loadVersions(refresh: Boolean) = this@MainActivity.loadVersions(refresh)
        override fun install(source: InstallManager.Source) = this@MainActivity.install(source)
        override fun pickTarball() = this@MainActivity.pickTarball()
        override fun rollBack() = this@MainActivity.rollBack()
        override fun askPermissions() = this@MainActivity.askPermissions()
        override fun openBatterySettings() = this@MainActivity.openBatterySettings()
        override fun setAutostart(on: Boolean) = this@MainActivity.setAutostart(on)
        override fun finishSetup(startServer: Boolean) = this@MainActivity.finishSetup(startServer)
        override fun openRuntimeChecks() = this@MainActivity.openRuntimeChecks()
    }

    private fun startServer() {
        val wanted = ServerActions.missingPermissions(this)
        if (wanted.isEmpty()) SignalKService.start(this) else askThenStart.launch(wanted.toTypedArray())
    }

    private fun stopServer() = SignalKService.stop(this)

    private fun restartServer() = SignalKService.restart(this)

    private fun openAdmin() = ServerActions.openAdmin(this)

    /** npm's versions, kept 24 h by [InstallManager.listAvailable]; [refresh] asks npm again. */
    private fun loadVersions(refresh: Boolean) {
        if (model.available == Available.Loading) return
        if (!refresh && model.available is Available.Loaded) return
        model.available = Available.Loading
        transportThread("dock-versions", onError = { e -> runOnUiThread { model.available = Available.Failed("$e") } }) {
            val result = try {
                Available.Loaded(installs.listAvailable(maxAgeMs = if (refresh) 0 else InstallManager.VERSIONS_MAX_AGE_MS))
            } catch (e: IOException) {
                Available.Failed(e.message ?: "$e")
            }
            runOnUiThread { model.available = result }
        }
    }

    private fun install(source: InstallManager.Source) {
        model.install = null
        if (ServerActions.install(this, runtime, source)) model.installing = true
    }

    private fun pickTarball() = pickFile.launch(TARBALL_TYPES)

    /** Back to the other slot's version, by hand: no version is marked bad, and a running server moves to it. */
    private fun rollBack() {
        val app = applicationContext
        transportThread("dock-rollback", onError = { e -> ServerStatus.log(app).note("rollback failed: $e") }) {
            val to = installs.rollback(bad = false, reason = getString(R.string.dock_rollback_reason))
            ServerStatus.log(app).note(to?.let { "rolled back by hand: slot $it is live" } ?: "nothing to roll back to")
            if (to != null) SignalKService.promoted(app)
            refreshSlots()
        }
    }

    private fun askPermissions() {
        val wanted = ServerActions.missingPermissions(this)
        if (wanted.isNotEmpty()) askOnly.launch(wanted.toTypedArray())
    }

    /**
     * The list of apps the battery optimiser leaves alone, where the user picks this one. Not the
     * one-tap request dialog: that needs a permission Google Play allows only for a few kinds of
     * app, and lint flags it, while the list asks for nothing.
     */
    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.perm_battery_none, Toast.LENGTH_LONG).show()
        }
    }

    private fun setAutostart(on: Boolean) {
        prefs.autostart = on
        refreshPermissions()
    }

    private fun finishSetup(startServer: Boolean) {
        prefs.setupDone = true
        setupDone = true
        if (startServer) startServer()
    }

    private fun openRuntimeChecks() {
        startActivity(Intent(this, SpikeActivity::class.java))
    }

    // ---- reads -----------------------------------------------------------------------------------

    /** The slots, their versions and the history: file reads, so off the main thread. */
    private fun refreshSlots() {
        transportThread("dock-slots", onError = {}) {
            val live = installs.liveSlot()
            val other = Slots.inactive(live)
            val view = SlotsView(
                live = live,
                liveVersion = live?.let { installs.installedVersion(it) },
                other = other,
                otherVersion = if (live == null) null else installs.installedVersion(other),
                history = installs.history(),
            )
            runOnUiThread { model.slots = view }
        }
    }

    private fun refreshAddress() {
        transportThread("dock-address", onError = {}) {
            val address = LanAddress.ofThisPhone()
            runOnUiThread { model.lanAddress = address }
        }
    }

    private fun refreshPermissions() {
        fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        model.permissions = PermissionsView(
            notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                granted(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                null
            },
            localNetwork = if (LocalNetwork.forServer(Build.VERSION.SDK_INT, lanAccess = true) == LocalNetwork.Need.REQUIRED) {
                granted(LocalNetwork.PERMISSION)
            } else {
                null
            },
            batteryUnrestricted = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true,
            autostart = prefs.autostart,
        )
    }

    /**
     * Copies the picked `.tgz` into the app's cache, where npm can read it as a file: a document
     * from the system picker is a content URI, not a path. One sideload kept at a time.
     */
    private fun copyTarball(uri: Uri) {
        val target = File(cacheDir, "sideload/signalk-server.tgz")
        transportThread("dock-sideload", onError = { e -> ServerStatus.log(this).note("could not read $uri: $e") }) {
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment ?: target.name
            target.parentFile?.mkdirs()
            val input = contentResolver.openInputStream(uri) ?: throw IOException("$uri cannot be opened")
            input.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
            runOnUiThread {
                model.tarball = target
                model.tarballName = name
            }
        }
    }

    companion object {
        /** Starts the server as the Dock's Start would: from the Quick Settings tile. */
        const val ACTION_START = "fi.pocketsk.action.OPEN_AND_START"

        private const val LOG_LINES = 60

        /** What a `.tgz` from `npm pack` is called, depending on who serves it. */
        private val TARBALL_TYPES = arrayOf("application/gzip", "application/x-gzip", "application/x-tar", "application/octet-stream")
    }
}
