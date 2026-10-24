package fi.pocketsk.server

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * Files a laptop copied over USB, for the server's data folders (docs/PLAN.md §16 9.5.10). A
 * computer on the phone's USB (MTP) sees the phone's shared storage only, never the app's own
 * `.signalk`, so the app keeps an inbox there, `Android/data/fi.pocketsk/files/inbox`, with a
 * folder for each data folder (`inbox/charts` to begin with), and takes what is put in it into
 * `.signalk/<folder>`, wherever that is ([DataStore]: the phone, or a card).
 *
 * A file is taken once it has been still for [SETTLE_MS], since a copy over USB may still be
 * writing it. It is copied into the folder's staging beside it, on its volume (the bridge plugin's
 * uploads use the same, `.pocketsk-uploads`), renamed into the folder whole, over a file of the
 * same name, and then deleted from the inbox: the charts plugin never sees half a chart.
 *
 * Plain java.nio, unit-tested on the host's file system, with the clock passed in. Blocking: a
 * worker thread or the supervisor's only, and one at a time ([LOCK]).
 */
internal class Inbox(
    private val inbox: File,
    private val configDir: File,
    private val note: (String) -> Unit,
    private val free: (File) -> Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** What waits in the inbox: [files] of [bytes] in all, those still being written among them. */
    data class Waiting(val files: Int, val bytes: Long)

    /** Makes the inbox and its `charts`, so a computer finds them; false when they cannot be made. */
    fun prepare(): Boolean = File(inbox, "charts").let { it.isDirectory || it.mkdirs() }

    fun waiting(): Waiting {
        var files = 0
        var bytes = 0L
        for ((_, f) in candidates(settledOnly = false)) {
            files++
            bytes += f.length()
        }
        return Waiting(files, bytes)
    }

    /**
     * Takes every settled file into its data folder; returns them as `<folder>/<name>`. A file that
     * cannot be taken (no room) stays in the inbox, said in [note], and the others are still taken.
     */
    fun take(): List<String> = synchronized(LOCK) {
        val taken = mutableListOf<String>()
        for ((folder, f) in candidates(settledOnly = true)) {
            try {
                takeOne(folder, f)
                taken += "$folder/${f.name}"
            } catch (e: IOException) {
                note("$folder/${f.name} stays in the inbox: ${e.message ?: e}")
            }
        }
        taken
    }

    private fun takeOne(folder: String, f: File) {
        val dest = File(configDir, folder)
        Files.createDirectories(dest.toPath())
        val real = dest.toPath().toRealPath()
        val staging = real.resolveSibling(UPLOADS).resolve(folder)
        Files.createDirectories(staging)
        val size = f.length()
        val room = free(staging.toFile())
        if (room < size + MARGIN) throw IOException("it needs ${size / MB} MB, and there are ${room / MB} MB free")
        val part = staging.resolve(f.name + PART)
        Files.copy(f.toPath(), part, StandardCopyOption.REPLACE_EXISTING)
        part.toFile().setLastModified(f.lastModified())
        Files.move(part, real.resolve(f.name), StandardCopyOption.REPLACE_EXISTING)
        Files.delete(f.toPath())
    }

    /** The inbox's files by their folder: plain, visible files in a data folder's name. */
    private fun candidates(settledOnly: Boolean): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        for (d in inbox.listFiles().orEmpty().sortedBy { it.name }) {
            if (!Files.isDirectory(d.toPath(), LinkOption.NOFOLLOW_LINKS) || !isDataFolder(d.name)) continue
            for (f in d.listFiles().orEmpty().sortedBy { it.name }) {
                if (f.name.startsWith(".") || !Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
                if (settledOnly && now() - f.lastModified() < SETTLE_MS) continue
                out += d.name to f
            }
        }
        return out
    }

    companion object {
        /** Under the app's own folder on the phone's shared storage. */
        const val DIR = "inbox"

        /** How long a file must be unchanged before it is taken. */
        const val SETTLE_MS = 30_000L

        /** One take at a time in the process: the Dock's button and a server start may meet. */
        private val LOCK = Any()

        /** The bridge plugin's staging folder name (sk-plugin/android-bridge/lib/files.js). */
        private const val UPLOADS = ".pocketsk-uploads"
        private const val PART = ".inbox"
        private const val MB = 1024L * 1024
        private const val MARGIN = 64 * MB
        private val FOLDER = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

        /** The same rule as the plugin's: a plain name, not the plugins nor their settings. */
        fun isDataFolder(name: String): Boolean =
            FOLDER.matches(name) && name != "node_modules" && name != DataStore.PLUGIN_DATA &&
                !name.endsWith(".partial") && !name.endsWith(".moved")
    }
}
