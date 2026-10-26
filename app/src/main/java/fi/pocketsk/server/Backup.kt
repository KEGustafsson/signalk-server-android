package fi.pocketsk.server

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the Dock's backup holds and how it goes back (docs/PLAN.md §16 10.8): the server's settings,
 * as Signal K's own backup has them, in an [AesZip] the owner keeps where they like. That is every
 * file directly in `.signalk` (`settings.json`, `security.json` and the server's secret key, the
 * SSL key, `package.json`, `baseDeltas.json`, …) and the plugins' settings files in
 * `plugin-config-data`: what [ConfigMirror] copies to the USB folder, and for the same reasons
 * nothing else. `node_modules` stays out, the plugins being installed again from `package.json`
 * after a restore, as Signal K's restore does; so do the data folders, charts above all, which can
 * be gigabytes and are in the USB folder already.
 *
 * A restore checks the whole backup before it writes anything: every name must be one [collect]
 * could have written, and none may land on a folder or a link ([DataStore]'s data folders are
 * links in `.signalk`). The files are written into [restore]'s staging folder first, readable by
 * the app's user alone, then moved into place one by one; a file the backup does not hold is
 * left as it is, as Signal K's restore leaves it.
 *
 * Plain java.nio, unit-tested on the host's file system. Blocking, and the caller holds
 * [DataStore.LOCK], so neither the data's placing nor the USB folder's copies run meanwhile.
 */
internal object Backup {

    /**
     * Whether [p] may encrypt a backup: [MIN_PASSPHRASE] to [MAX_PASSPHRASE] printable ASCII
     * characters. Long, because the format's own key stretching is weak ([AesZip]); ASCII, so it
     * is the same bytes on every phone and in 7-Zip on a computer.
     */
    fun passphraseOk(p: CharArray): Boolean = p.size in MIN_PASSPHRASE..MAX_PASSPHRASE && p.all { it in ' '..'~' }

    /** The backup's files in [configDir], by their path under it. Throws when there is nothing to back up. */
    @Throws(IOException::class)
    fun collect(configDir: File): List<AesZip.Entry> {
        val entries = buildList {
            fun scan(dir: File, prefix: String) {
                for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
                    if (!Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS) || !kept(f.name)) continue
                    if (f.length() > ConfigMirror.MAX_BYTES) continue
                    add(AesZip.Entry(prefix + f.name, f.readBytes(), f.lastModified()))
                }
            }
            scan(configDir, "")
            scan(File(configDir, DataStore.PLUGIN_DATA), "${DataStore.PLUGIN_DATA}/")
        }
        if (entries.isEmpty()) throw IOException("there is nothing in $configDir to back up")
        return entries
    }

    /**
     * Writes [entries] into [configDir] by way of [staging], a folder on the same file system that
     * is emptied first and removed afterwards; the names written, in order. Throws, having
     * written nothing, when one is not a name [collect] writes or would land on a folder or a link.
     */
    @Throws(IOException::class)
    fun restore(configDir: File, staging: File, entries: List<AesZip.Entry>): List<String> {
        if (entries.isEmpty()) throw IOException("the backup holds no files")
        val names = entries.map { it.name }
        names.firstOrNull { !restorable(it) }?.let { throw IOException("the backup holds $it, which is not a settings file") }
        names.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.let { throw IOException("the backup holds ${it.key} twice") }
        for (name in names) {
            val p = File(configDir, name).toPath()
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("$name is a folder or a link in ${configDir.name}, not a file to restore over")
            }
        }
        staging.deleteRecursively()
        Files.createDirectories(File(staging, DataStore.PLUGIN_DATA).toPath())
        try {
            for (e in entries) {
                val f = File(staging, e.name)
                f.writeBytes(e.data)
                ownerOnly(f)
                if (e.modified > 0) f.setLastModified(e.modified)
            }
            Files.createDirectories(File(configDir, DataStore.PLUGIN_DATA).toPath())
            for (name in names) {
                Files.move(File(staging, name).toPath(), File(configDir, name).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
        } finally {
            staging.deleteRecursively()
        }
        return names
    }

    /**
     * Whether [rel], a path read from a backup, is one [collect] could have written: a file directly
     * in `.signalk` or in its `plugin-config-data`, by a name [kept] accepts. A backup is opened by
     * its passphrase alone, so a name with `..` or a slash must not take a file anywhere else.
     */
    fun restorable(rel: String): Boolean {
        val parts = rel.split('/')
        val placed = parts.size == 1 || (parts.size == 2 && parts[0] == DataStore.PLUGIN_DATA)
        val name = parts.last()
        return placed && name.isNotEmpty() && name != "." && name != ".." && '\\' !in name && '\u0000' !in name && kept(name)
    }

    /** The name a new backup is offered under: `pocketsk-backup-2026-10-26-1930.zip`, in local time. */
    fun fileName(now: Long): String = "pocketsk-backup-${SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(Date(now))}.zip"

    /**
     * Whether a file named [name] is backed up: not a dot file (`.npmrc`, ours), a log, nor a
     * server's or [ConfigMirror]'s half-written save. Signal K's own backup leaves out the same.
     */
    private fun kept(name: String): Boolean =
        !name.startsWith(".") && !name.endsWith(".log") && !name.endsWith(".tmp") && !name.endsWith(ConfigMirror.PART)

    private fun ownerOnly(f: File) {
        try {
            Files.setPosixFilePermissions(f.toPath(), OWNER_ONLY)
        } catch (_: UnsupportedOperationException) {
            // Not a POSIX file system: there is no one else to keep out.
        }
    }

    private val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")

    const val MIN_PASSPHRASE = 12
    const val MAX_PASSPHRASE = 64
}
