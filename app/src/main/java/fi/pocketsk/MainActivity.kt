package fi.pocketsk

import android.app.NotificationManager
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import fi.pocketsk.bridge.BleScanner
import fi.pocketsk.bridge.BluetoothSerial
import fi.pocketsk.bridge.SerialPrefs
import fi.pocketsk.bridge.UsbSerial
import fi.pocketsk.install.InstallJob
import fi.pocketsk.install.InstallManager
import fi.pocketsk.install.Slots
import fi.pocketsk.net.transportThread
import fi.pocketsk.node.NodeRuntime
import fi.pocketsk.server.DataVolumes
import fi.pocketsk.server.LanAddress
import fi.pocketsk.server.ServerPrefs
import fi.pocketsk.server.ServerState
import fi.pocketsk.server.ServerStatus
import fi.pocketsk.server.ServerUsage
import fi.pocketsk.server.SignalKService
import fi.pocketsk.ui.AppActions
import fi.pocketsk.ui.AppModel
import fi.pocketsk.ui.Available
import fi.pocketsk.ui.DockScreen
import fi.pocketsk.ui.PermissionsView
import fi.pocketsk.ui.PocketTheme
import fi.pocketsk.ui.SetupScreen
import fi.pocketsk.ui.SlotsView
import fi.pocketsk.ui.StorageView
import java.io.File
import java.io.IOException
import java.nio.file.Files

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
    private val usage = ServerUsage { model.usage = it }

    /** A restartWithLocation stop is on its way; the start follows once the server has stopped. Main thread. */
    private var restartPending = false

    /** Counts the sideload picks, main thread only, so a slow copy cannot replace a newer one. */
    private var picks = 0

    /** Null until the first read of the prefs and the slots says which screen comes first. */
    private var setupDone by mutableStateOf<Boolean?>(null)

    private val serverObserver = ServerStatus.Observer { state ->
        val wasActive = model.server.isActive
        model.server = state
        model.log = ServerStatus.log(this).tail(LOG_LINES)
        model.devices = ServerStatus.devices
        model.alarms = ServerStatus.alarms
        model.locationHeld = ServerStatus.locationHeld
        model.deltaRate = ServerStatus.deltaRate
        finishRestart()
        // A start, a rollback or an end changes what is live or where it is reached.
        if (state is ServerState.Starting || state.isActive != wasActive) refreshSlots()
        if (state is ServerState.Running) refreshAddress()
        // A move under way or just done changes what is free where.
        if (state is ServerState.Storage || state is ServerState.Starting || state.isActive != wasActive) refreshStorage()
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

    /** Precise and approximate together, as Android asks; GNSS needs the precise one. */
    private val askLocationLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermissions()
        SignalKService.permissionsChanged(this)
    }

    /** Bluetooth for the paired serial devices and the Bluetooth LE scan; the bridge looks again whatever the answer. */
    private val askBluetoothLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermissions()
        SignalKService.permissionsChanged(this)
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
        // Before the observer, which may finish it at once: a restart asked for before a rotation.
        restartPending = savedInstanceState?.getBoolean(STATE_RESTART_PENDING) == true
        ServerStatus.observe(serverObserver)
        // Not from onResume, which runs before the lifecycle says RESUMED: AndroidX moves it there
        // only once onResume has returned, so the check in finishRestart would always fail.
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        finishRestart()
                        usage.start()
                    }
                    // The figures are for this screen only: nothing reads /proc while it is hidden.
                    Lifecycle.Event.ON_PAUSE -> usage.stop()
                    else -> Unit
                }
            },
        )
        InstallJob.observe(installObserver)
        refreshSlots()
        refreshStorage()
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
        // A card put in or taken out while the app was in the background.
        refreshStorage()
        // With the app in front a running server may take location, which it could not when it
        // was started at boot; or it must give it up, taken back in the settings.
        SignalKService.permissionsChanged(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_RESTART_PENDING, restartPending)
    }

    /**
     * The second half of restartWithLocation, once the server has stopped: started again from
     * here it takes location. Only with this screen in front, since that is what gives the start
     * location (and lets it start at all); a stop that ends while the screen is hidden waits for it
     * to come back.
     */
    private fun finishRestart() {
        if (!restartPending || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (ServerStatus.state.isActive) {
            // The stop cleared running; set again, a server was started since, and the restart a
            // stale saved state still asks for is done (or no longer wanted).
            if (prefs.running) restartPending = false
            return
        }
        restartPending = false
        startServer()
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
        override fun askLocation() = this@MainActivity.askLocation()
        override fun openBatterySettings() = this@MainActivity.openBatterySettings()
        override fun setAutostart(on: Boolean) = this@MainActivity.setAutostart(on)
        override fun finishSetup(startServer: Boolean) = this@MainActivity.finishSetup(startServer)
        override fun openRuntimeChecks() = this@MainActivity.openRuntimeChecks()
        override fun levelAttitude() = SignalKService.level(this@MainActivity)

        override fun allowDevice(address: String) = UsbSerial.askPermission(this@MainActivity, address)

        override fun chooseBluetooth(address: String, chosen: Boolean) {
            SerialPrefs(this@MainActivity).choose(address, chosen)
            SignalKService.devicesChanged(this@MainActivity)
        }

        override fun askBluetooth() = askBluetoothPermission()

        override fun silenceAlarm(id: String) = SignalKService.silence(this@MainActivity, id)

        override fun openFullScreenSettings() = this@MainActivity.openFullScreenSettings()

        override fun restartWithLocation() {
            if (!model.server.isActive) return
            restartPending = true
            stopServer()
        }

        override fun chooseStorage(volume: DataVolumes.Volume?) = this@MainActivity.chooseStorage(volume)
    }

    private fun startServer() {
        val wanted = ServerActions.missingPermissions(this)
        if (wanted.isEmpty()) SignalKService.start(this) else askThenStart.launch(wanted.toTypedArray())
    }

    private fun stopServer() = SignalKService.stop(this)

    /**
     * The data's new place, which the supervisor moves it to before the next run: a running
     * server restarts for it now, a stopped one moves it when it is started.
     */
    private fun chooseStorage(volume: DataVolumes.Volume?) {
        prefs.dataVolume = volume?.uuid
        prefs.dataVolumeName = volume?.name
        SignalKService.restart(this)
        refreshStorage()
    }

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
        // Refused because one is running already (begun elsewhere, and not yet past its first
        // state): this screen follows that one, whose states reach it through the observer.
        model.installing = ServerActions.install(this, runtime, source) || InstallJob.isRunning
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
     * Asks for precise location, or, once Android has stopped showing the dialog (refused twice,
     * or "don't ask again"), opens this app's own settings page, where it can still be allowed.
     * Asked before and no rationale is how that refusal looks; the first request looks the same
     * but for [ServerPrefs.locationAsked].
     */
    private fun askLocation() {
        val fine = android.Manifest.permission.ACCESS_FINE_LOCATION
        if (prefs.locationAsked && !shouldShowRequestPermissionRationale(fine)) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                return
            } catch (_: ActivityNotFoundException) {
                // No settings page on this build: the request below is all there is.
            }
        }
        prefs.locationAsked = true
        askLocationLauncher.launch(arrayOf(fine, android.Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    /**
     * Asks for Bluetooth (Nearby devices: talking to paired devices and scanning for Bluetooth LE
     * ones, one dialog for both) on Android 12 and later, or, once Android has stopped showing the
     * dialog for every one still missing, opens this app's settings page, as [askLocation] does.
     */
    private fun askBluetoothPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val connect = android.Manifest.permission.BLUETOOTH_CONNECT
        val scan = android.Manifest.permission.BLUETOOTH_SCAN
        val missing = listOf(connect, scan).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return
        // Android shows no rationale both before a first request and after a refusal it will not
        // ask again; the flags tell the two apart, one per permission, since scanning came later.
        val asked = mapOf(connect to prefs.bluetoothAsked, scan to prefs.bluetoothScanAsked)
        if (missing.all { asked[it] == true && !shouldShowRequestPermissionRationale(it) }) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                return
            } catch (_: ActivityNotFoundException) {
                // No settings page on this build: the request below is all there is.
            }
        }
        prefs.bluetoothAsked = true
        prefs.bluetoothScanAsked = true
        askBluetoothLauncher.launch(missing.toTypedArray())
    }

    /**
     * Android 14 and later's page for this app's full-screen intents, which the alarm needs to
     * wake a locked phone; the app's own settings page where a build has no such page.
     */
    private fun openFullScreenSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val app = Uri.fromParts("package", packageName, null)
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, app))
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app))
            } catch (_: ActivityNotFoundException) {
                // Nothing to open on this build.
            }
        }
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

    /** The cards and their room: Android makes its folders on them as it lists them, so off the main thread. */
    private fun refreshStorage() {
        transportThread("dock-storage", onError = {}) {
            val files = filesDir
            val view = StorageView(
                chosen = prefs.dataVolume,
                chosenName = prefs.dataVolumeName,
                phoneFree = DataVolumes.free(files),
                phoneTotal = DataVolumes.total(files),
                volumes = DataVolumes.list(this),
            )
            runOnUiThread { model.storage = view }
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
            location = granted(android.Manifest.permission.ACCESS_FINE_LOCATION),
            // Below Android 12 both are install-time permissions; the scan's location is the row above.
            bluetooth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) BluetoothSerial.allowed(this) && BleScanner.allowed(this) else true,
            fullScreen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
            } else {
                null
            },
            batteryUnrestricted = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true,
            autostart = prefs.autostart,
        )
    }

    /**
     * Copies the picked `.tgz` into the app's cache, where npm can read it as a file: a document
     * from the system picker is a content URI, not a path. Each pick gets a directory of its own,
     * the file written under a temporary name and renamed to the picked file's name when whole
     * (that name is what the install history records), so a second pick never writes over the
     * file a running install is reading, and only the newest pick becomes the choice.
     */
    private fun copyTarball(uri: Uri) {
        val pick = ++picks
        val app = applicationContext
        transportThread("dock-sideload", onError = { e -> ServerStatus.log(app).note("could not read $uri: $e") }) {
            val shown = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment
            val name = shown?.replace(UNSAFE_NAME, "_")?.trim('.', '_')?.takeIf { it.isNotEmpty() && it != PARTIAL } ?: "signalk-server.tgz"
            val dir = Files.createTempDirectory(File(cacheDir, SIDELOAD_DIR).apply { mkdirs() }.toPath(), "pick-").toFile()
            val partial = File(dir, PARTIAL)
            val target = File(dir, name)
            try {
                val input = contentResolver.openInputStream(uri) ?: throw IOException("$uri cannot be opened")
                input.use { i -> partial.outputStream().use { o -> i.copyTo(o) } }
                if (!partial.renameTo(target)) throw IOException("cannot rename $PARTIAL to $name")
            } catch (e: IOException) {
                dir.deleteRecursively()
                throw e
            }
            runOnUiThread { pickCopied(pick, target, shown ?: name) }
        }
    }

    /**
     * A copy is whole: the choice if it is the newest pick, else dropped. The picks before it are
     * deleted unless an install is running, which may be reading one of them, and except those
     * still being copied; one left behind that way goes with the next pick made while nothing
     * installs, or with the cache.
     */
    private fun pickCopied(pick: Int, file: File, name: String) {
        val dir = file.parentFile ?: return
        if (pick != picks) {
            dir.deleteRecursively()
            return
        }
        model.tarball = file
        model.tarballName = name
        if (!InstallJob.isRunning) {
            File(cacheDir, SIDELOAD_DIR).listFiles()
                ?.filter { it != dir && !File(it, PARTIAL).exists() }
                ?.forEach { it.deleteRecursively() }
        }
    }

    companion object {
        /** Starts the server as the Dock's Start would: from the Quick Settings tile. */
        const val ACTION_START = "fi.pocketsk.action.OPEN_AND_START"

        private const val LOG_LINES = 60

        /** Saved across a rotation: a restartWithLocation whose start is still to come. */
        private const val STATE_RESTART_PENDING = "restart_pending"

        /** Under the cache: the sideloaded tarballs, a directory per pick. */
        private const val SIDELOAD_DIR = "sideload"

        /** A pick's file while it is being copied. */
        private const val PARTIAL = "copying.part"

        /** What a picked file's name may not carry into a path or an npm argument. */
        private val UNSAFE_NAME = Regex("[^A-Za-z0-9._-]")

        /** What a `.tgz` from `npm pack` is called, depending on who serves it. */
        private val TARBALL_TYPES = arrayOf("application/gzip", "application/x-gzip", "application/x-tar", "application/octet-stream")
    }
}
