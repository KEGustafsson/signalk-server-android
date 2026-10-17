package fi.pocketsk

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import fi.pocketsk.node.NodeRuntime
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Spike R1 (docs/PLAN.md §3): can this app exec the Node runtime from its native library
 * directory? The screen shows where the runtime is and the environment it gets, and the button
 * runs `node -v`, then unpacks npm and runs `npm -v` (node running npm-cli.js, the first real
 * script), printing whatever comes back, including the exact exception when the exec is
 * refused. It becomes the Dock once the service exists.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var runtime: NodeRuntime
    private lateinit var output: TextView

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
        run.isEnabled = runtime.isAvailable
        val intro = buildString {
            append(runtime.describe())
            if (!runtime.isAvailable) appendLine().append(getString(R.string.spike_missing))
        }
        output.text = intro
        run.setOnClickListener {
            // One run at a time: a second tap would start a second node and, the first time, a
            // second unpack of npm. NodeRuntime serialises the unpack; this keeps the report whole.
            run.isEnabled = false
            runNodeVersion { run.isEnabled = true }
        }
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
            }
            report.appendLine().append(runtime.describe())
            runOnUiThread {
                output.text = report
                done()
            }
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
    }
}
