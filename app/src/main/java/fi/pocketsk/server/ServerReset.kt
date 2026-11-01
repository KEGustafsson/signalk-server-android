package fi.pocketsk.server

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * The Reset card's "Reset the server's settings" (docs/PLAN.md §16 15): `.signalk` back to what a
 * first start finds, the data kept. Removed: every file directly in `.signalk` (`settings.json`,
 * `security.json` with the users and the admin account, the SSL key and certificate, `package.json`
 * and the rest), the plugins and webapps in `node_modules`, and the plugins' settings files in
 * `plugin-config-data`. Kept: the data folders beside them and each plugin's data folder (charts,
 * routes and waypoints, server state, logs), wherever [DataStore] keeps them, links and all, and
 * the installed server versions, which are not in `.signalk`.
 *
 * The copies of the removed files in the folder a computer sees ([ConfigMirror]) go too, so the
 * next start takes nothing of them back in. Their manifest is kept: a copy that is gone and was
 * last copied is simply forgotten, and on a card that is out now the next sync deletes the copies
 * the computer has not changed, as it does for any file the server deleted.
 *
 * Plain java.nio, unit-tested on the host's file system; it never follows a link into what it
 * deletes (`node_modules` holds links to the bridge's plugin and to npm's bins). Blocking, with
 * the server stopped, under [DataStore.LOCK]: the supervisor's own rule for `.signalk`.
 */
internal object ServerReset {

    /**
     * Resets [configDir] as above, and [mirror] (the folder a computer sees; null when it is not
     * there now). Returns what was removed, by path under `.signalk`, `node_modules` as one.
     */
    @Throws(IOException::class)
    fun settings(configDir: File, mirror: File?): List<String> {
        val removed = mutableListOf<String>()
        for (f in configDir.listFiles().orEmpty().sortedBy { it.name }) {
            when {
                f.name == NODE_MODULES -> {
                    deleteTree(f.toPath())
                    removed += NODE_MODULES
                }
                isFile(f) -> {
                    Files.delete(f.toPath())
                    removed += f.name
                }
            }
        }
        for (f in File(configDir, DataStore.PLUGIN_DATA).listFiles().orEmpty().sortedBy { it.name }) {
            if (isFile(f)) {
                Files.delete(f.toPath())
                removed += "${DataStore.PLUGIN_DATA}/${f.name}"
            }
        }
        if (mirror != null) {
            for (rel in removed) {
                val copy = File(mirror, rel)
                if (rel != NODE_MODULES && isFile(copy)) Files.delete(copy.toPath())
            }
        }
        return removed
    }

    /** A file of its own, not a folder nor a link (a data folder's link is the data, kept). */
    private fun isFile(f: File) = Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS)

    /** [root] and what is under it, links removed as links and never followed. */
    @Throws(IOException::class)
    fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(root)
            return
        }
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private const val NODE_MODULES = "node_modules"
}
