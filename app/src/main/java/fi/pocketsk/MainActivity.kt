package fi.pocketsk

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import fi.pocketsk.node.NodeRuntime
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Spike R1 (docs/PLAN.md §3): can this app exec the Node runtime from its native library
 * directory? The screen shows where the runtime is and the environment it gets, and the button
 * runs `node -v`, then unpacks npm and runs `npm -v` (node running npm-cli.js, the first real
 * script), then spike R2: it copies the two builds of the test addon out of nativeLibraryDir into
 * writable storage and requires each from there, the way npm leaves a plugin's native addon in
 * node_modules. It prints whatever comes back, including the exact exception when an exec or a
 * load is refused. The second button is spike R3 ([SpikeInstall]): npm installs signalk-server
 * into slot A, its output streamed onto the screen. The third is spike R4 ([SpikeServer]): it
 * starts that server with sample data and opens its admin UI in the browser once it answers,
 * and stops it again. It becomes the Dock once the service exists.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var runtime: NodeRuntime
    private lateinit var output: TextView
    private lateinit var run: Button
    private lateinit var install: Button
    private lateinit var server: Button

    /** Whether this screen's R1/R2 run is going. Unlike the other two, it dies with the screen. */
    private var nodeRunning = false

    private val installObserver = SpikeInstall.Observer { text, _ ->
        runOnUiThread {
            output.text = text.ifEmpty { getString(R.string.spike_installing) }
            syncButtons()
        }
    }

    private val serverObserver = object : SpikeServer.Observer {
        override fun onProgress(text: String, running: Boolean) = runOnUiThread {
            if (text.isNotEmpty()) output.text = text
            syncButtons()
        }

        override fun onReady(ms: Long, generation: Long) = runOnUiThread {
            // Checked again here: Stop may have been tapped while this waited for the main thread.
            if (SpikeServer.isCurrent(generation)) openAdmin()
        }
    }

    /**
     * The admin UI in the phone's browser, the way it is used on any Signal K server: the app has
     * no viewer of its own for it (docs/PLAN.md §8.2).
     */
    private fun openAdmin() {
        val url = "${SpikeServer.BASE}/admin/"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.spike_server_no_browser, url), Toast.LENGTH_LONG).show()
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
        server = findViewById(R.id.server)
        val intro = buildString {
            append(runtime.describe())
            if (!runtime.isAvailable) appendLine().append(getString(R.string.spike_missing))
        }
        output.text = intro
        run.setOnClickListener {
            nodeRunning = true
            syncButtons()
            runNodeVersion {
                nodeRunning = false
                syncButtons()
            }
        }
        install.setOnClickListener {
            output.text = getString(R.string.spike_installing)
            SpikeInstall.start(runtime, File(filesDir, "logs/spike-r3.log")).observe(installObserver)
            syncButtons()
        }
        server.setOnClickListener {
            if (SpikeServer.isRunning) {
                SpikeServer.stop()
                return@setOnClickListener
            }
            output.text = getString(R.string.spike_server_starting)
            SpikeServer.observe(serverObserver)
            try {
                SpikeServer.start(this, runtime, File(filesDir, "logs/spike-r4.log"))
            } catch (e: IOException) {
                output.text = getString(R.string.spike_server_failed, e.toString())
            }
            syncButtons()
        }
        // An install or a server outlives the activity that started it (a rotation); this one
        // takes it over, its output and its end. An install that has ended shows its report,
        // since the report is what the spike is for; a running server's output wins over it.
        SpikeInstall.current?.observe(installObserver)
        if (SpikeServer.isRunning) SpikeServer.observe(serverObserver)
        syncButtons()
    }

    override fun onDestroy() {
        SpikeInstall.current?.detach(installObserver)
        SpikeServer.detach(serverObserver)
        super.onDestroy()
    }

    /**
     * One run at a time, of any kind: a second tap would start a second node and, the first time,
     * a second unpack of npm (NodeRuntime serialises it; this keeps the report whole); an install
     * deletes the slot a running server runs from. While the server runs its button stops it.
     */
    private fun syncButtons() {
        val installing = SpikeInstall.current?.isRunning == true
        val serving = SpikeServer.isRunning
        val idle = runtime.isAvailable && !nodeRunning && !installing && !serving
        run.isEnabled = idle
        // An install that killed npm keeps the slot until the app is force-stopped: a script it
        // started may still be writing there, so neither a new install nor a server starts from it.
        val slotHeld = SpikeInstall.current?.mayHaveOrphans == true
        install.isEnabled = idle && !slotHeld
        server.isEnabled = serving || (idle && !slotHeld)
        server.setText(if (serving) R.string.spike_server_stop else R.string.spike_server_start)
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

    /** Runs the process [start] makes, appends its output and exit to [report]; true when it exited 0. */
    private fun exec(report: StringBuilder, start: () -> Process): Boolean = try {
        val process = start()
        val text = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(SPIKE_TIMEOUT_S, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        report.appendLine(text.trimEnd())
        report.appendLine(if (finished) "exit ${process.exitValue()}" else "timed out after ${SPIKE_TIMEOUT_S}s")
        finished && process.exitValue() == 0
    } catch (e: IOException) {
        // EACCES here is SELinux refusing the exec; ENOENT is the file not being there; a
        // Tar.TarException is a damaged npm asset.
        report.appendLine("failed: $e")
        false
    }

    private companion object {
        const val PAD_DP = 16
        const val SPIKE_TIMEOUT_S = 20L

        /** native/spike-addon: one build linked like a generic prebuild, one linked to libnode.so. */
        val ADDONS = listOf("spike_napi", "spike_napi_linked")

        /** Requires each addon named on the command line, one line per addon, whatever happens. */
        const val R2_SCRIPT = "for (const f of process.argv.slice(1)) { " +
            "try { console.log(require('path').basename(f) + ': ' + require(f).hello()) } " +
            "catch (e) { console.log(require('path').basename(f) + ': FAILED ' + e.message) } }"
    }
}
