package fi.pocketsk

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import fi.pocketsk.install.InstallHistory
import fi.pocketsk.install.InstallJob
import fi.pocketsk.install.InstallManager
import fi.pocketsk.node.NodeProcess
import fi.pocketsk.node.NodeRuntime
import fi.pocketsk.server.ServerPrefs
import fi.pocketsk.server.ServerState
import fi.pocketsk.server.ServerStatus
import fi.pocketsk.server.SignalKService
import fi.pocketsk.server.Supervisor
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Spike R1 (docs/PLAN.md §3): can this app exec the Node runtime from its native library
 * directory? The screen shows where the runtime is and the environment it gets, and the button
 * runs `node -v`, then unpacks npm and runs `npm -v` (node running npm-cli.js, the first real
 * script), then spike R2: it copies the two builds of the test addon out of nativeLibraryDir into
 * writable storage and requires each from there, the way npm leaves a plugin's native addon in
 * node_modules. It prints whatever comes back, including the exact exception when an exec or a
 * load is refused. The second button is spike R3 ([SpikeInstall]): npm installs signalk-server
 * into slot A, its output streamed onto the screen. The third runs PLAN §16 step 9.2's
 * [InstallManager] through [InstallJob]: the latest release into the slot that is not live, its
 * smoke test, and the promotion, so the installer can be tried on a phone before the wizard
 * exists. The fourth starts and stops PLAN §16 step 9.3's [SignalKService], which runs the live
 * slot's server under its supervisor, and shows its state and log; once it answers, the fifth
 * opens the admin UI in the browser. (It replaced spike R4's button, which ran slot A's server
 * by hand.) It all becomes the Dock in step 9.4.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var runtime: NodeRuntime
    private lateinit var output: TextView
    private lateinit var run: Button
    private lateinit var install: Button
    private lateinit var upgrade: Button
    private lateinit var server: Button
    private lateinit var admin: Button
    private lateinit var autostart: CheckBox

    /** Whether this screen's R1/R2 run is going. Unlike the others, it dies with the screen. */
    private var nodeRunning = false

    /** Whose output the screen shows: the last thing started from it, or found running. */
    private enum class Showing { NOTHING, NODE, INSTALL, UPGRADE, SERVER }
    private var showing = Showing.NOTHING

    private val installObserver = SpikeInstall.Observer { text, _ ->
        runOnUiThread {
            if (showing == Showing.INSTALL) output.text = text.ifEmpty { getString(R.string.spike_installing) }
            syncButtons()
        }
    }

    private val upgradeObserver = InstallJob.Observer { state, _ ->
        runOnUiThread {
            if (showing != Showing.UPGRADE) return@runOnUiThread syncButtons()
            output.text = when (state) {
                is InstallManager.State.Installing -> getString(R.string.spike_upgrade_installing, state.slot, state.tail)
                is InstallManager.State.Verifying ->
                    getString(R.string.spike_upgrade_verifying, state.slot, InstallManager.SMOKE_PORT, state.tail)
                is InstallManager.State.Done ->
                    getString(R.string.spike_upgrade_done, state.version, state.slot, historyText(state.history))
                is InstallManager.State.Failed -> getString(R.string.spike_upgrade_failed, state.reason, state.tail)
            }
            syncButtons()
        }
    }

    private val serverObserver = ServerStatus.Observer { state ->
        if (showing == Showing.SERVER) output.text = serverText(state)
        syncButtons()
    }

    /**
     * What the service needs asked for first: notifications (Android 13+), without which it runs
     * unseen, and Android 17's local network access, without which only the phone itself reaches
     * the server ([LocalNetwork]). It starts whatever the answers, and says the rest in its log.
     */
    private val askThenStart = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        SignalKService.start(this)
    }

    private fun startServer() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (LocalNetwork.forServer(Build.VERSION.SDK_INT, lanAccess = true) == LocalNetwork.Need.REQUIRED) add(LocalNetwork.PERMISSION)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isEmpty()) SignalKService.start(this) else askThenStart.launch(wanted.toTypedArray())
    }

    /** The state on top, the newest of the log below it. */
    private fun serverText(state: ServerState): String {
        val head = when (state) {
            ServerState.Stopped -> getString(R.string.spike_service_stopped)
            is ServerState.Starting -> getString(R.string.server_title, state.version) + "  " + getString(R.string.server_starting)
            is ServerState.Running -> getString(R.string.server_title, state.version) + "  " + when {
                !state.answering -> getString(R.string.server_not_answering, Supervisor.BASE)
                state.onTrial -> getString(R.string.server_on_trial, Supervisor.BASE)
                else -> Supervisor.BASE
            }
            is ServerState.Waiting -> getString(R.string.server_waiting, state.reason, state.delayMs / 1000)
            ServerState.Stopping -> getString(R.string.server_stopping)
            is ServerState.Failed -> getString(R.string.spike_service_failed, state.reason)
        }
        return head + "\n\n" + ServerStatus.log(this).tail(SpikeInstall.TAIL_LINES)
    }

    /**
     * The admin UI in the phone's browser, the way it is used on any Signal K server: the app has
     * no viewer of its own for it (docs/PLAN.md §8.2).
     */
    private fun openAdmin() {
        val url = "${Supervisor.BASE}/admin/"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.spike_service_no_browser, url), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val root = findViewById<android.view.View>(R.id.root)
        val pad = (PAD_DP * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            // Edge to edge: the layout pads itself with the bars.
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom + pad)
            insets
        }
        runtime = NodeRuntime(this)
        output = findViewById(R.id.output)
        run = findViewById(R.id.run)
        install = findViewById(R.id.install)
        upgrade = findViewById(R.id.upgrade)
        server = findViewById(R.id.server)
        admin = findViewById(R.id.admin)
        autostart = findViewById(R.id.autostart)
        val intro = buildString {
            append(runtime.describe())
            if (!runtime.isAvailable) appendLine().append(getString(R.string.spike_missing))
        }
        output.text = intro
        run.setOnClickListener {
            showing = Showing.NODE
            nodeRunning = true
            syncButtons()
            runNodeVersion {
                nodeRunning = false
                syncButtons()
            }
        }
        install.setOnClickListener {
            showing = Showing.INSTALL
            output.text = getString(R.string.spike_installing)
            SpikeInstall.start(runtime, File(filesDir, "logs/spike-r3.log")).observe(installObserver)
            syncButtons()
        }
        upgrade.setOnClickListener {
            showing = Showing.UPGRADE
            output.text = getString(R.string.spike_upgrade_starting)
            InstallJob.observe(upgradeObserver)
            val app = applicationContext
            // A running server moves to the new version at once, under its health gate.
            InstallJob.start(InstallManager(this, runtime), InstallManager.Source.Registry()) {
                SignalKService.promoted(app, it.version)
            }
            syncButtons()
        }
        server.setOnClickListener {
            showing = Showing.SERVER
            if (ServerStatus.state.isActive) SignalKService.stop(this) else startServer()
            output.text = serverText(ServerStatus.state)
        }
        admin.setOnClickListener { openAdmin() }
        val prefs = ServerPrefs(this)
        autostart.isChecked = prefs.autostart
        autostart.setOnCheckedChangeListener { _, on -> prefs.autostart = on }
        // An install or a server outlives the activity that started it (a rotation); this one
        // takes it over, its output and its end. A running server's output wins; else an
        // install's report, since the report is what the spike is for.
        showing = when {
            ServerStatus.state.isActive -> Showing.SERVER
            InstallJob.isRunning -> Showing.UPGRADE
            SpikeInstall.current != null -> Showing.INSTALL
            else -> Showing.NOTHING
        }
        SpikeInstall.current?.observe(installObserver)
        InstallJob.observe(upgradeObserver)
        ServerStatus.observe(serverObserver)
        syncButtons()
    }

    override fun onDestroy() {
        SpikeInstall.current?.detach(installObserver)
        InstallJob.detach(upgradeObserver)
        ServerStatus.detach(serverObserver)
        super.onDestroy()
    }

    /**
     * One spike run at a time: a second tap would start a second node and, the first time, a
     * second unpack of npm (NodeRuntime serialises it; this keeps the report whole). R3 wipes
     * slot A, which may be the live one, so it waits for the server to stop; InstallManager's
     * install goes to the slot that is not live and may run beside it. While the server runs its
     * button stops it.
     */
    private fun syncButtons() {
        val state = ServerStatus.state
        val serving = state.isActive
        val spiking = nodeRunning || SpikeInstall.current?.isRunning == true || InstallJob.isRunning
        val idle = runtime.isAvailable && !spiking
        run.isEnabled = idle
        // An install that killed npm keeps slot A until the app is force-stopped: a script it
        // started may still be writing there, so neither a new install nor a server starts from it.
        val slotHeld = SpikeInstall.current?.mayHaveOrphans == true
        install.isEnabled = idle && !slotHeld && !serving
        upgrade.isEnabled = idle && !slotHeld
        server.isEnabled = serving || (runtime.isAvailable && SpikeInstall.current?.isRunning != true && !slotHeld)
        server.setText(if (serving) R.string.spike_service_stop else R.string.spike_service_start)
        admin.isEnabled = state is ServerState.Running && state.answering
    }

    /** [history], newest first, one line each, for the screen after an install. */
    private fun historyText(history: InstallHistory): String = history.newestFirst.joinToString("\n") {
        "${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.at))}  ${it.outcome}  ${it.version}  " +
            "slot ${it.slot}  ${it.source}" + (it.detail?.let { d -> "  ($d)" } ?: "")
    }

    private fun runNodeVersion(done: () -> Unit) {
        output.text = getString(R.string.spike_running)
        thread(name = "spike-node") {
            val report = StringBuilder()
            report.appendLine("$ node -v")
            val nodeRan = exec(report) { runtime.spawn("-v") }
            if (nodeRan) {
                report.appendLine().appendLine("$ npm -v")
                exec(report) {
                    val started = System.nanoTime()
                    runtime.ensureExtracted()
                    report.appendLine("(npm ready in ${(System.nanoTime() - started) / 1_000_000} ms)")
                    runtime.npm("-v")
                }
                report.appendLine().appendLine("$ node -e 'require(<addon in files/>)'   # R2")
                exec(report) { runtime.spawn("-e", R2_SCRIPT, *copyAddons().map { it.absolutePath }.toTypedArray()) }
            }
            report.appendLine().append(runtime.describe())
            runOnUiThread {
                output.text = report
                done()
            }
        }
    }

    /**
     * The test addon's two builds, copied from nativeLibraryDir to files/prebuilds/spike as
     * `.node` files: writable storage, where npm puts every native addon a plugin brings.
     */
    private fun copyAddons(): List<File> {
        val dir = File(runtime.prebuilds, "spike").apply { mkdirs() }
        return ADDONS.map { name ->
            File(dir, "$name.node").also { File(runtime.nativeLibraryDir, "lib$name.so").copyTo(it, overwrite = true) }
        }
    }

    /** Runs the node [start] makes, appends its output and exit to [report]; true when it exited 0. */
    private fun exec(report: StringBuilder, start: () -> NodeProcess): Boolean {
        val node = try {
            start()
        } catch (e: IOException) {
            // EACCES here is SELinux refusing the exec; ENOENT is the file not being there; a
            // Tar.TarException is a damaged npm asset.
            report.appendLine("failed: $e")
            return false
        }
        val process = node.process
        // Read on a thread of its own, so the timeout runs while the node does: a read on this
        // thread ends only at end of stream, which a hung node, or a child holding its output,
        // never reaches, so the run would never end and every button would stay off.
        val out = StringBuilder()
        val failure = AtomicReference<IOException?>()
        val reader = thread(name = "spike-node-out", isDaemon = true) {
            try {
                process.inputStream.bufferedReader().forEachLine { synchronized(out) { out.appendLine(it) } }
            } catch (e: IOException) {
                failure.set(e)
            }
        }
        val finished = process.waitFor(SPIKE_TIMEOUT_S, TimeUnit.SECONDS)
        if (!finished) {
            node.kill()
            process.waitFor()
        }
        // Nothing the run started outlives it, and reaping closes the pipe for the reader.
        val grouped = node.reap()
        reader.join(DRAIN_MS)
        report.appendLine(synchronized(out) { out.toString() }.trimEnd())
        if (!grouped) report.appendLine("(no process group: the launcher could not make one)")
        val failed = failure.get()
        report.appendLine(
            when {
                failed != null -> "failed: $failed"
                finished -> "exit ${process.exitValue()}"
                else -> "timed out after ${SPIKE_TIMEOUT_S}s"
            },
        )
        return failed == null && finished && process.exitValue() == 0
    }

    private companion object {
        const val PAD_DP = 16
        const val SPIKE_TIMEOUT_S = 20L

        /** How long a run's report waits for the last of its output once its group is gone. */
        const val DRAIN_MS = 2_000L

        /** native/spike-addon: one build linked like a generic prebuild, one linked to libnode.so. */
        val ADDONS = listOf("spike_napi", "spike_napi_linked")

        /** Requires each addon named on the command line, one line per addon, whatever happens. */
        const val R2_SCRIPT = "for (const f of process.argv.slice(1)) { " +
            "try { console.log(require('path').basename(f) + ': ' + require(f).hello()) } " +
            "catch (e) { console.log(require('path').basename(f) + ': FAILED ' + e.message) } }"
    }
}
