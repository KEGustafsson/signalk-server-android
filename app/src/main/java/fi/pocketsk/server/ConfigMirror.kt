package fi.pocketsk.server

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/**
 * The files that stay in `.signalk` on the phone, shown in the folder a computer sees over USB
 * (docs/PLAN.md §16 9.5.11): `settings.json`, `security.json`, the SSL key and certificate,
 * `package.json` and every other file directly in `.signalk`, and the plugins' settings files in
 * `plugin-config-data`, kept as copies in [root], the folder where [DataStore] keeps the data
 * folders themselves. So that folder holds all of `.signalk` but `node_modules`, as
 * `/home/node/.signalk` does on a Linux box.
 *
 * Copies, not the files and not links to them: the server saves its settings by writing a new
 * file and renaming it over the old, which would put a file of its own in place of a link, and
 * it refuses to start with an SSL key or keeps failing to save `security.json` where it cannot
 * make them readable by its own user alone, which shared storage and a card cannot do. The live
 * files stay in the app's private storage with their modes; only the copies are out here.
 *
 * Both ways, by one rule. A copy changed, added or deleted on the computer goes into `.signalk`
 * at the next start ([sync] with `running` false), before the server reads it; while the server
 * runs, that copy is left as the computer made it and the server's own changes to the file wait
 * behind it. Every other copy follows the phone's file. Deleting a copy deletes the file, as on a
 * Linux box: that is how a forgotten password is reset (`security.json`). What was last copied
 * either way is recorded in [MANIFEST] in [root]: a copy that differs from it is the computer's.
 * Without one (a folder new or emptied) nothing is deleted, and a copy found there counts as the
 * computer's.
 *
 * Plain java.nio, unit-tested on the host's file system. Blocking: the supervisor's threads only,
 * under [DataStore.LOCK].
 */
internal class ConfigMirror(
    private val configDir: File,
    private val root: File,
    /** The text of [README] in [root]: what the folder is, for whoever opens it on a computer. */
    private val readme: String,
    private val note: (String) -> Unit,
) {
    /**
     * What one [sync] did: [taken] the files that went into `.signalk` from the computer (or were
     * deleted there because their copy was), [waiting] the computer's changes left for the next
     * start because the server runs.
     */
    data class Result(val taken: List<String>, val waiting: List<String>)

    /** Brings [root]'s copies and `.signalk`'s files in line, the computer's changes going in only when not [running]. */
    @Throws(IOException::class)
    fun sync(running: Boolean): Result {
        Files.createDirectories(root.toPath())
        Files.createDirectories(File(root, DataStore.PLUGIN_DATA).toPath())
        sweep(configDir)
        sweep(File(configDir, DataStore.PLUGIN_DATA))
        sweep(root)
        sweep(File(root, DataStore.PLUGIN_DATA))
        val known = readManifest()
        val next = sortedMapOf<String, String>()
        val taken = mutableListOf<String>()
        val waiting = mutableListOf<String>()
        val names = sortedSetOf<String>().apply {
            addAll(files(configDir, phone = true))
            addAll(files(root, phone = false))
            addAll(known.keys)
        }
        for (rel in names) {
            val here = File(configDir, rel)
            val there = File(root, rel)
            // A folder or a link on either side is not a settings file: DataStore's, or the owner's.
            if (other(here) || other(there)) continue
            val mine = digest(here)
            val theirs = digest(there)
            val last = known[rel]
            val edited = theirs != null && theirs != last && theirs != mine
            val deleted = theirs == null && last != null && mine != null
            when {
                edited && running -> {
                    waiting += rel
                    last?.let { next[rel] = it }
                }
                edited -> {
                    copyIn(there, here)
                    next[rel] = theirs!!
                    taken += rel
                }
                deleted && running -> {
                    waiting += rel
                    next[rel] = last!!
                }
                deleted -> {
                    Files.delete(here.toPath())
                    taken += rel
                }
                mine != null -> {
                    if (mine != theirs) copyOut(here, there)
                    next[rel] = mine
                }
                // The server deleted its file: the copy goes too, unless the computer changed it.
                theirs != null -> Files.delete(there.toPath())
            }
        }
        writeManifest(next)
        writeReadme()
        return Result(taken, waiting)
    }

    /**
     * The settings files directly in [dir] and in its `plugin-config-data`, by their path under
     * it; on the computer's side ([phone] false) without the files a computer leaves (`.DS_Store`)
     * and this class's own.
     */
    private fun files(dir: File, phone: Boolean): List<String> = buildList {
        fun scan(d: File, prefix: String) {
            for (f in d.listFiles().orEmpty()) {
                val name = f.name
                if (!Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS) || !mirrored(name, phone)) continue
                if (f.length() > MAX_BYTES) {
                    note("${prefix + name} is over ${MAX_BYTES / MB} MB and is not copied to the USB folder")
                    continue
                }
                add(prefix + name)
            }
        }
        scan(dir, "")
        scan(File(dir, DataStore.PLUGIN_DATA), "${DataStore.PLUGIN_DATA}/")
    }

    /** Whether a file named [name] is copied: not a server's half-written save, nor ours, nor a computer's litter. */
    private fun mirrored(name: String, phone: Boolean): Boolean {
        if (name.endsWith(".tmp") || name.endsWith(PART)) return false
        if (phone) return true
        return name != MANIFEST && name != README && !name.startsWith("._") && name !in LITTER
    }

    /** A copy from the computer into `.signalk`, readable by the app's user alone, as the server keeps its own. */
    private fun copyIn(from: File, to: File) {
        to.parentFile?.let { Files.createDirectories(it.toPath()) }
        val part = File(to.path + PART)
        Files.copy(from.toPath(), part.toPath(), StandardCopyOption.REPLACE_EXISTING)
        try {
            Files.setPosixFilePermissions(part.toPath(), OWNER_ONLY)
        } catch (_: UnsupportedOperationException) {
            // Not a POSIX file system: there is no one else to keep out.
        }
        Files.move(part.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** A copy of the phone's file for the computer, written whole and renamed over the last one. */
    private fun copyOut(from: File, to: File) {
        val part = File(to.path + PART)
        Files.copy(from.toPath(), part.toPath(), StandardCopyOption.REPLACE_EXISTING)
        part.setLastModified(from.lastModified())
        Files.move(part.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /** Half a copy left by a process killed in [copyIn] or [copyOut]. */
    private fun sweep(dir: File) {
        for (f in dir.listFiles().orEmpty()) if (f.name.endsWith(PART) && f.isFile) Files.delete(f.toPath())
    }

    private fun other(f: File): Boolean =
        Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS)

    /** [f]'s SHA-256 in hex, or null when it is not there. */
    private fun digest(f: File): String? {
        if (!Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** The manifest: a line per file, its digest, a tab and its path. */
    private fun readManifest(): Map<String, String> {
        val f = File(root, MANIFEST)
        if (!f.isFile) return emptyMap()
        return f.readLines().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else line.substring(tab + 1) to line.substring(0, tab)
        }.toMap()
    }

    private fun writeManifest(entries: Map<String, String>) {
        val text = entries.entries.joinToString("") { (rel, sum) -> "$sum\t$rel\n" }
        val f = File(root, MANIFEST)
        if (f.isFile && f.readText() == text) return
        val part = File(f.path + PART)
        part.writeText(text)
        Files.move(part.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun writeReadme() {
        val f = File(root, README)
        if (f.isFile && f.readText() == readme) return
        f.writeText(readme)
    }

    companion object {
        /** What was last copied either way, in [root]. A dot file, so a computer's view does not show it by default. */
        const val MANIFEST = ".pocketsk-copies"

        /** What the folder is, for whoever opens it on a computer. */
        const val README = "README.txt"

        private const val PART = ".pocketsk-part"

        /** What a computer's file manager leaves behind in a folder it showed. */
        private val LITTER = setOf(".DS_Store", "Thumbs.db", "desktop.ini")

        private val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")

        private const val MB = 1024L * 1024

        /** A file bigger than this directly in `.signalk` is no settings file, and is not copied every 30 s. */
        private const val MAX_BYTES = 16 * MB

        private const val BUFFER = 64 * 1024
    }
}
