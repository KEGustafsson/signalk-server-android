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
 * directory? The screen shows where the runtime would be and the environment it would get, and
 * the button runs `node -v` and prints whatever comes back, including the exact exception when
 * the exec is refused. It becomes the Dock once the runtime module and the service exist.
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
        run.setOnClickListener { runNodeVersion() }
    }

    private fun runNodeVersion() {
        output.text = getString(R.string.spike_running)
        thread(name = "spike-node") {
            val report = StringBuilder(runtime.describe()).appendLine().appendLine("$ node -v")
            try {
                val process = runtime.spawn("-v")
                val text = process.inputStream.bufferedReader().readText()
                val finished = process.waitFor(SPIKE_TIMEOUT_S, TimeUnit.SECONDS)
                if (!finished) process.destroyForcibly()
                report.appendLine(text.trimEnd())
                report.appendLine(if (finished) "exit ${process.exitValue()}" else "timed out after ${SPIKE_TIMEOUT_S}s")
            } catch (e: IOException) {
                // EACCES here is SELinux refusing the exec; ENOENT is the file not being there.
                report.appendLine("exec failed: $e")
            }
            runOnUiThread { output.text = report }
        }
    }

    private companion object {
        const val PAD_DP = 16
        const val SPIKE_TIMEOUT_S = 20L
    }
}
