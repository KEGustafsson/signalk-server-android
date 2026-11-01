package fi.pocketsk.server

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
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
 * The settings files' copies in the folder a computer sees ([ConfigMirror]) go too, a copy only
 * the computer has among them, so the next start takes nothing back in; its manifest and README
 * stay. The removed names are also added to the marker a restore keeps for the next sync
 * ([Backup.restored]): on a card that is out now, the next sync after it is back deletes their
 * copies however the computer changed them, rather than take an edited one in.
 *
 * Plain java.nio, unit-tested on the host's file system; it never follows a link into what it
 * deletes (`node_modules` holds links to the bridge's plugin and to npm's bins). Blocking, with
 * the server stopped, under [DataStore.LOCK]: the supervisor's own rule for `.signalk`.
 */
internal object ServerReset {

    /**
     * Resets [configDir] as above, and [mirror] (the folder a computer sees; null when it is not
     * there now), adding the removed settings files to [marker] first, so a process killed half
     * way still leaves the next sync knowing them. Returns what was removed, by path under
     * `.signalk`, `node_modules` as one.
     */
    @Throws(IOException::class)
    fun settings(configDir: File, mirror: File?, marker: File): List<String> {
        val files = settingsFiles(configDir)
        val modules = File(configDir, NODE_MODULES)
        if (files.isNotEmpty()) {
            marker.parentFile?.let { Files.createDirectories(it.toPath()) }
            val part = File(marker.path + ConfigMirror.PART)
            part.writeText((Backup.restored(marker) + files).toSortedSet().joinToString("") { "$it\n" })
            Files.move(part.toPath(), marker.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        val removed = mutableListOf<String>()
        if (Files.exists(modules.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            deleteTree(modules.toPath())
            removed += NODE_MODULES
        }
        for (rel in files) {
            Files.delete(File(configDir, rel).toPath())
            removed += rel
        }
        if (mirror != null) {
            for (rel in settingsFiles(mirror)) {
                if (rel != ConfigMirror.MANIFEST && rel != ConfigMirror.README) Files.delete(File(mirror, rel).toPath())
            }
        }
        return removed
    }

    /**
     * The settings files in [dir], by path under it: the files directly in it and in its
     * `plugin-config-data`, which [ConfigMirror] copies both ways. Folders and links are data.
     */
    private fun settingsFiles(dir: File): List<String> = buildList {
        for (f in dir.listFiles().orEmpty().sortedBy { it.name }) if (isFile(f)) add(f.name)
        for (f in File(dir, DataStore.PLUGIN_DATA).listFiles().orEmpty().sortedBy { it.name }) {
            if (isFile(f)) add("${DataStore.PLUGIN_DATA}/${f.name}")
        }
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
