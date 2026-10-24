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
 * Where the server's data lives (docs/PLAN.md §16 9.5.9): in `.signalk` on the phone, as Signal K
 * keeps it, or on a card the owner chose in the Dock, in the app's own folder there, which Android
 * gives without a permission and removes with the app.
 *
 * `.signalk` itself stays on the phone, and with it every file directly in it (`settings.json`,
 * `security.json`, the SSL key, `package.json`), `node_modules` and the plugins' settings files in
 * `plugin-config-data`: the server's passwords and the plugins' credentials stay in the phone's
 * encrypted storage, and an SD card's FAT32 or exFAT has no symbolic links, which npm and the
 * bridge's plugin need in `node_modules`. What goes to the card is every folder beside those,
 * charts, server state, application data, logs, and each plugin's data folder in
 * `plugin-config-data`: copied there, and replaced on the phone by a symbolic link to the copy, so
 * the server and its plugins find it where they always did. [place] does it before every start,
 * so a folder a plugin made during the last run follows on the next one.
 *
 * Every move is a copy into a staging folder (`.partial`) and a rename, and the source is renamed
 * aside (`.moved`) before it is deleted, so a process killed at any point leaves the data whole in
 * one place, and the next [place] carries on from what it finds. A card that already holds a
 * folder (charts copied onto it with a PC) is merged with, the phone's copy of a name taken there
 * kept as `<name>.from-phone`.
 *
 * Plain java.nio, so it is unit-tested on the host's file system; it never follows a link into
 * what it deletes. Blocking, and a large folder takes minutes to copy: the supervisor's thread only.
 */
internal class DataStore(
    private val configDir: File,
    private val note: (String) -> Unit,
    /** The bytes free on the volume of a folder ([DataVolumes.free] on the phone). */
    private val free: (File) -> Long,
) {
    /**
     * Brings `.signalk` in line with [root], the folder on a card (null: the phone): folders on the
     * phone go there, links to another card's or a vanished card's folder come back first, and a
     * folder only the card has is linked. [moving] is told the folder's path under `.signalk` before
     * each copy. Throws [IOException] when a folder cannot be moved, with the data left whole where
     * it was; a folder whose card is gone comes back to the phone empty, said in [note], since its
     * data cannot be fetched and the server must still start.
     */
    @Throws(IOException::class)
    fun place(root: File?, moving: (String) -> Unit = {}) {
        if (root != null) {
            if (!root.isDirectory && !root.mkdirs()) throw IOException("cannot make $root")
            sweep(root)
            sweep(File(root, PLUGIN_DATA))
        }
        recover(configDir)
        recover(File(configDir, PLUGIN_DATA))
        for (rel in folders(root)) {
            val here = File(configDir, rel)
            val there = root?.let { File(it, rel) }
            val linked = linkTarget(here, rel)
            when {
                linked != null && there != null && linked.absolutePath == there.absolutePath -> {
                    // Ours already. A card emptied on a PC gets the folder back, empty.
                    if (!there.isDirectory) there.mkdirs()
                }
                linked != null -> {
                    moving(rel)
                    back(here, linked, rel)
                    if (there != null) toCard(here, there, rel)
                }
                there == null -> Unit
                isDir(here) -> {
                    moving(rel)
                    toCard(here, there, rel)
                }
                !exists(here) && isDir(there) -> {
                    here.parentFile?.mkdirs()
                    Files.createSymbolicLink(here.toPath(), there.toPath())
                }
            }
        }
    }

    /**
     * For a store made on a copy of `.signalk` (the installer's smoke test, made with `cp -a`,
     * which keeps links as links): this store's links become empty folders, so a server run on the
     * copy writes none of the live data on the card, which the live server may be writing then.
     */
    @Throws(IOException::class)
    fun detach() {
        for (rel in folders(null)) {
            val f = File(configDir, rel)
            if (linkTarget(f, rel) != null) {
                Files.delete(f.toPath())
                Files.createDirectories(f.toPath())
            }
        }
    }

    /**
     * The data folders, by their path under `.signalk`: those on the phone (real folders and this
     * store's links) and, given a [root], those only the card has. Sorted, so the moves come in a
     * stable order.
     */
    fun folders(root: File?): List<String> {
        val found = sortedSetOf<String>()
        fun scan(dir: File, prefix: String, keep: Set<String>, card: Boolean) {
            for (f in dir.listFiles().orEmpty()) {
                val name = f.name
                if (name in keep || name.startsWith(".") || name.endsWith(PARTIAL) || name.endsWith(MOVED)) continue
                val rel = prefix + name
                if (isDir(f) || (!card && linkTarget(f, rel) != null)) found += rel
            }
        }
        scan(configDir, "", KEEP, card = false)
        scan(File(configDir, PLUGIN_DATA), "$PLUGIN_DATA/", emptySet(), card = false)
        if (root != null) {
            scan(root, "", KEEP, card = true)
            scan(File(root, PLUGIN_DATA), "$PLUGIN_DATA/", emptySet(), card = true)
        }
        return found.toList()
    }

    /** Where [here] links to, when it is one of this store's links: one ending in `signalk/<rel>`. */
    private fun linkTarget(here: File, rel: String): File? {
        if (!Files.isSymbolicLink(here.toPath())) return null
        val target = try {
            Files.readSymbolicLink(here.toPath())
        } catch (_: IOException) {
            return null
        }
        val file = (if (target.isAbsolute) target else here.toPath().resolveSibling(target)).toFile()
        return file.takeIf { it.path.endsWith("/$CARD_DIR/$rel") }
    }

    /** The folder at [link] comes back to the phone from [src]: copied, the link replaced, the card's copy deleted. */
    private fun back(link: File, src: File, rel: String) {
        val partial = File(link.path + PARTIAL)
        deleteTree(partial)
        // A move interrupted after the card's copy was renamed aside: that is the data.
        val from = if (isDir(src)) src else File(src.path + MOVED).takeIf { isDir(it) }
        if (from != null) {
            copyTree(from, partial, rel, "the phone")
        } else {
            note("$rel was on a card that is not there; it starts again empty")
            Files.createDirectories(partial.toPath())
        }
        Files.delete(link.toPath())
        Files.move(partial.toPath(), link.toPath())
        if (from != null) {
            val moved = if (from == src) File(src.path + MOVED).also { Files.move(src.toPath(), it.toPath()) } else from
            deleteTree(moved)
        }
    }

    /** The folder [here] goes to [there] on the card: copied (merged with one there), then linked. */
    private fun toCard(here: File, there: File, rel: String) {
        there.parentFile?.let { Files.createDirectories(it.toPath()) }
        val staging = File(there.path + PARTIAL)
        deleteTree(staging)
        copyTree(here, staging, rel, "the card")
        if (!exists(there)) Files.move(staging.toPath(), there.toPath()) else merge(staging, there)
        val moved = File(here.path + MOVED)
        Files.move(here.toPath(), moved.toPath())
        Files.createSymbolicLink(here.toPath(), there.toPath())
        deleteTree(moved)
    }

    /** [staging]'s entries into [dest], which has some already: a name taken there gets `.from-phone`. */
    private fun merge(staging: File, dest: File) {
        for (f in staging.listFiles().orEmpty()) {
            var target = File(dest, f.name)
            var n = 1
            while (exists(target)) {
                target = File(dest, f.name + FROM_PHONE + if (n == 1) "" else "-$n")
                n++
            }
            if (target.name != f.name) note("${dest.name}/${f.name} is on the card already; the phone's copy is ${target.name}")
            Files.move(f.toPath(), target.toPath())
        }
        deleteTree(staging)
    }

    /**
     * What an interrupted [place] left on the phone in [dir]: a `.partial` whose folder is gone is
     * one whose link was removed after the copy ended, so it is put in place; any other `.partial`
     * is half a copy, and a `.moved` is what was already copied: both are deleted.
     */
    private fun recover(dir: File) {
        for (f in dir.listFiles().orEmpty()) {
            when {
                f.name.endsWith(PARTIAL) -> {
                    val whole = File(dir, f.name.removeSuffix(PARTIAL))
                    if (!exists(whole)) Files.move(f.toPath(), whole.toPath()) else deleteTree(f)
                }
                f.name.endsWith(MOVED) -> deleteTree(f)
            }
        }
    }

    /** On a card: a `.partial` is half a copy and a `.moved` a source already copied. */
    private fun sweep(dir: File) {
        for (f in dir.listFiles().orEmpty()) {
            if (f.name.endsWith(PARTIAL) || f.name.endsWith(MOVED)) deleteTree(f)
        }
    }

    /**
     * [from]'s tree into [to], which does not exist yet. Files only, their times kept where the
     * file system can: a card's has no owners or modes to copy, and a link inside a data folder is
     * copied as the file it names (a FAT card cannot hold one). Checks the room on [to]'s volume
     * first, so a card that cannot take the folder refuses it before anything is written.
     */
    private fun copyTree(from: File, to: File, rel: String, where: String) {
        val size = treeSize(from)
        val room = free(to.parentFile ?: to)
        if (room < size + MARGIN) {
            throw IOException("$rel needs ${size / MB} MB, and $where has ${room / MB} MB free")
        }
        val src = from.toPath()
        val dst = to.toPath()
        Files.walkFileTree(src, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.createDirectories(dst.resolve(src.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val out = dst.resolve(src.relativize(file).toString())
                when {
                    attrs.isRegularFile -> copyFile(file, out)
                    attrs.isSymbolicLink && Files.isRegularFile(file) && phoneOnly(file) ->
                        note("$rel: left out $file, a link to a file that stays on the phone")
                    attrs.isSymbolicLink && Files.isRegularFile(file) -> copyFile(file, out)
                    else -> note("$rel: left out $file, which is not a file")
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    /**
     * Whether the link [link] reaches a file that stays on the phone: one directly in `.signalk`
     * (`security.json`, the SSL key), in `node_modules`, or a plugin's settings file. Copied, its
     * secret would be on the card for anyone who takes it out.
     */
    private fun phoneOnly(link: Path): Boolean {
        val home = try {
            configDir.toPath().toRealPath()
        } catch (_: IOException) {
            return false
        }
        val target = try {
            link.toRealPath()
        } catch (_: IOException) {
            return false
        }
        return target.startsWith(home) &&
            (target.parent == home || target.startsWith(home.resolve("node_modules")) || target.parent == home.resolve(PLUGIN_DATA))
    }

    private fun copyFile(file: Path, out: Path) {
        Files.copy(file, out, StandardCopyOption.REPLACE_EXISTING)
        // A FAT card keeps times to two seconds and may refuse some: the copy stands without.
        out.toFile().setLastModified(file.toFile().lastModified())
    }

    private fun treeSize(dir: File): Long {
        var total = 0L
        Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                total += if (attrs.isSymbolicLink) file.toFile().length() else attrs.size()
                return FileVisitResult.CONTINUE
            }
        })
        return total
    }

    /** Deletes [f] and, when it is a folder and not a link, all under it; never follows a link. */
    private fun deleteTree(f: File) {
        val path = f.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(path)
            return
        }
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun isDir(f: File) = Files.isDirectory(f.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun exists(f: File) = Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)

    companion object {
        /** The data's folder in the app's own folder on a card. */
        const val CARD_DIR = "signalk"

        /** Where Signal K keeps the plugins' settings files and, beside them, their data folders. */
        const val PLUGIN_DATA = "plugin-config-data"

        /** What stays in `.signalk` on the phone whatever is chosen: the plugins, and the plugins' settings. */
        private val KEEP = setOf("node_modules", PLUGIN_DATA)

        private const val PARTIAL = ".partial"
        private const val MOVED = ".moved"
        private const val FROM_PHONE = ".from-phone"

        private const val MB = 1024L * 1024
        /** Room kept free on the volume a folder is copied to, for the server's own writes after it. */
        private const val MARGIN = 64 * MB
    }
}
