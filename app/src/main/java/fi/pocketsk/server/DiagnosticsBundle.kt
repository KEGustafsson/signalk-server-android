package fi.pocketsk.server

import android.content.Context
import fi.pocketsk.BuildConfig
import fi.pocketsk.node.NodeRuntime
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Gathers the diagnostics bundle on the phone and writes it with [Diagnostics], as a zip in the
 * app's cache (`cache/diagnostics/`, shared from there by the manifest's FileProvider and by
 * nothing else). Each bundle has a name of its own, and the ones before it stay for [KEEP_MS]
 * (at most [KEEP_COUNT] of them): the app that took one may still be reading it when the owner
 * shares again, and a share that pointed at a deleted file would fail there, not here. Older
 * ones are deleted, so the cache holds no pile of old logs. Blocking: a worker thread only.
 *
 * What goes in: a README saying what is there and what is not; `device.txt`, the app's version,
 * the server's state as the Dock sees it and [NodeRuntime.describe] (the phone, its ABIs and RAM,
 * the runtime's environment, which holds paths and no secret); `settings.txt`, the app's own
 * preferences ([Diagnostics.settings]); `install-history.json`; and `logs/`, the server's log files
 * ([LogFiles]). The server's configuration directory is never read ([Diagnostics.write]).
 */
internal object DiagnosticsBundle {

    /**
     * The app's preferences files by name ([ServerPrefs], [fi.pocketsk.bridge.SensorPrefs],
     * [fi.pocketsk.bridge.SerialPrefs]). A list on purpose rather than every file in the
     * preferences folder: a file added later, which might hold a token, stays out until someone
     * adds it here knowing what is in it.
     */
    private val PREFS = listOf("server", "sensors", "serial")

    private const val DIR = "diagnostics"

    /** How long a bundle is kept after it was made, for the app it was shared with to read. */
    private const val KEEP_MS = 60 * 60 * 1000L

    /** At most this many earlier bundles are kept, however recent. */
    private const val KEEP_COUNT = 3

    /** Writes a new bundle, with [summary] (the Dock's view of the server) in `device.txt`, and returns it. */
    @Throws(IOException::class)
    fun write(context: Context, runtime: NodeRuntime, summary: String): File {
        val dir = File(context.cacheDir, DIR)
        val now = Date()
        prune(dir, now.time)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not make $dir")
        val file = File(dir, "pocketsk-diagnostics-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(now)}.zip")
        val files = context.filesDir
        val logs = File(files, "logs")
        val prefs = PREFS.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all }
        val texts = listOf(
            Diagnostics.Text("README.txt", README),
            Diagnostics.Text("device.txt", device(now, runtime, summary)),
            Diagnostics.Text("settings.txt", Diagnostics.settings(prefs)),
        )
        val copies = buildList {
            add(Diagnostics.Copy("install-history.json", File(files, "sk/install-history.json")))
            add(Diagnostics.Copy("logs/server.log", File(logs, "server.log")))
            for (i in 1..LogFiles.KEEP) add(Diagnostics.Copy("logs/server.log.$i", File(logs, "server.log.$i")))
        }
        try {
            file.outputStream().use { Diagnostics.write(it, texts, copies, runtime.configDir) }
        } catch (e: IOException) {
            file.delete()
            throw e
        }
        return file
    }

    /** Deletes the bundles older than [KEEP_MS], and all but the newest [KEEP_COUNT] of the rest. */
    private fun prune(dir: File, now: Long) {
        val old = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        old.forEachIndexed { i, f -> if (i >= KEEP_COUNT || now - f.lastModified() > KEEP_MS) f.delete() }
    }

    private fun device(now: Date, runtime: NodeRuntime, summary: String): String = buildString {
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        appendLine("made: ${utc.format(now)}")
        appendLine("app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.GIT_SHA})")
        appendLine()
        appendLine(summary.trimEnd())
        appendLine()
        append(runtime.describe())
    }

    private val README = """
        Pocket SK diagnostics

        device.txt            the app's version, the server's state, the phone and the runtime
        settings.txt          the app's own settings (keys that name a secret are left out)
        install-history.json  the versions installed on this phone and how each one went
        logs/                 the server's output, newest in server.log, older in server.log.1 to .3

        Not here: anything from the server's own settings folder (.signalk): security, users,
        tokens, the plugins' settings. Bluetooth addresses are cut to their last two bytes.
        The log is what the server and its plugins printed; read it before you post it in public.
    """.trimIndent() + "\n"
}
