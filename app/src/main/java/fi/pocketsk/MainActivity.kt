package fi.pocketsk

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
 * into slot A, its output streamed onto the screen. It becomes the Dock once the service exists.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var runtime: NodeRuntime
    private lateinit var output: TextView
    private var observer: SpikeInstall.Observer? = null

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
        val run = findViewById<Button>(R.id.run)
        val install = findViewById<Button>(R.id.install)
        run.isEnabled = runtime.isAvailable
        install.isEnabled = runtime.isAvailable
        val intro = buildString {
            append(runtime.describe())
            if (!runtime.isAvailable) appendLine().append(getString(R.string.spike_missing))
        }
        output.text = intro
        // One run at a time, of either kind: a second tap would start a second node and, the
        // first time, a second unpack of npm. NodeRuntime serialises the unpack; this keeps the
        // report whole and keeps two installs out of one slot.
        val buttons = listOf(run, install)
        run.setOnClickListener {
            buttons.forEach { it.isEnabled = false }
            runNodeVersion { buttons.forEach { it.isEnabled = true } }
        }
        val progress = SpikeInstall.Observer { text, finished ->
            runOnUiThread {
                output.text = text.ifEmpty { getString(R.string.spike_installing) }
                if (finished) buttons.forEach { it.isEnabled = runtime.isAvailable }
            }
        }
        observer = progress
        install.setOnClickListener {
            buttons.forEach { it.isEnabled = false }
            output.text = getString(R.string.spike_installing)
            SpikeInstall.start(runtime, File(filesDir, "logs/spike-r3.log")).observe(progress)
        }
        // An install outlives the activity that started it (a rotation); this one takes it over.
        SpikeInstall.current?.takeIf { it.isRunning }?.let {
            buttons.forEach { b -> b.isEnabled = false }
            it.observe(progress)
        }
    }

    override fun onDestroy() {
        observer?.let { SpikeInstall.current?.detach(it) }
        super.onDestroy()
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
