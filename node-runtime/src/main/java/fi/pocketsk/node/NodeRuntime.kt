package fi.pocketsk.node

import android.content.Context
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Where the Node runtime lives and how it is run (docs/PLAN.md §4).
 *
 * The runtime is not a library this app calls into; it is a real executable, `libnodeexec.so`
 * in the APK's native library directory, started with [ProcessBuilder] like `node` on a Pi.
 * That is what lets `npm`, child processes and `process.execPath` work unmodified inside
 * Signal K Server. Android only lets an app execute files from [nativeLibraryDir], never from
 * its writable storage, and only when the libraries are extracted at install time
 * (`jniLibs.useLegacyPackaging` in the build, which sets `extractNativeLibs`).
 *
 * The on-disk layout under [files] is the one the plan draws:
 *
 *     files/home            $HOME; .npmrc and .signalk live here
 *     files/sk/slots/{A,B}  npm global prefixes: lib/node_modules/signalk-server, as on a Pi
 *     files/sk/current      symlink to the live slot
 *     files/npm             npm itself (bin/npm-cli.js, lib, node_modules), extracted from the APK
 *     files/bin             node, a symlink into nativeLibraryDir; later the node-gyp shim
 *     files/prebuilds       cache of native addon builds fetched by the node-gyp shim
 *     cache/tmp             $TMPDIR
 *     no_backup/groups      one file per running [spawn], the id of its process group ([NodeProcess])
 *
 * Nothing here is created until [ensureLayout], and npm is not unpacked until [ensureExtracted].
 * The executable comes from this module's native build; on an ABI it does not build for,
 * [executable] is absent and [isAvailable] false, which is what the spike screen reports.
 *
 * There is no `files/bin/npm` wrapper script, though the plan once drew one. An app that targets
 * API 29 or later may not exec() a file in its own data directory, so a script there could be
 * run only as `sh files/bin/npm`, never by name from a lifecycle script; npm is started as
 * `node files/npm/bin/npm-cli.js` ([npm]) instead. Whether npm's lifecycle scripts get by under
 * that rule is spike R3 (docs/PLAN.md §3, §15).
 */
class NodeRuntime(context: Context) {
    private val app = context.applicationContext
    private val files: File = app.filesDir
    private val cache: File = app.cacheDir

    /** The directory the installer extracted the APK's native libraries to. Executable. */
    val nativeLibraryDir: File = File(app.applicationInfo.nativeLibraryDir)

    /** The Node executable, if this build carries one. */
    val executable: File = File(nativeLibraryDir, EXEC_NAME)
    val isAvailable: Boolean get() = executable.isFile

    val home = File(files, "home")
    val configDir = File(home, ".signalk")
    val slots = File(files, "sk/slots")
    val current = File(files, "sk/current")
    val npmDir = File(files, "npm")
    val binDir = File(files, "bin")
    val prebuilds = File(files, "prebuilds")
    val tmp = File(cache, "tmp")

    /**
     * Where the launcher records each spawned node's process group; see [NodeProcess]. Not in the
     * cache: Android may clear that while the app runs, and a server's record is first read when
     * it is stopped, hours later perhaps; a lost record leaves the group unreachable. no_backup is
     * never cleared by the system and never backed up, which pids have no business in either.
     */
    val groups = File(app.noBackupFilesDir, "groups")

    /** npm's entry point, once [ensureExtracted] has unpacked it. */
    val npmCli = File(npmDir, "bin/npm-cli.js")

    /**
     * Creates the directories and points `files/bin/node` at this install's executable.
     * Idempotent, and cheap enough to call on every start.
     *
     * The link is checked every time, not only created once: nativeLibraryDir is a new random
     * path after every app update, so a link made by the previous install dangles. A dangling
     * link is not [File.exists], and creating the link again over it fails with EEXIST, which
     * would leave `node` off PATH for good after the first update. Throws [IOException] when the
     * link cannot be made, like every other failure to set up the runtime.
     *
     * Under [LAYOUT_LOCK], like [ensureExtracted]: two callers that both find the link stale would
     * otherwise both delete it, and the second to recreate it fails with EEXIST.
     */
    @Throws(IOException::class)
    fun ensureLayout(): Unit = synchronized(LAYOUT_LOCK) {
        for (d in listOf(home, configDir, slots, npmDir, binDir, prebuilds, tmp, groups)) d.mkdirs()
        // Group records left by an earlier app process: nothing in them is alive any more, since
        // Android ends an app's processes, its children's too, with the app. Their ids are pids
        // that may since belong to someone else, so they are dropped once, before any spawn here.
        if (!groupsSwept) {
            groups.listFiles()?.forEach { it.delete() }
            groupsSwept = true
        }
        if (!isAvailable) return
        // A symlink into nativeLibraryDir, so `node` is on PATH for npm's lifecycle scripts.
        // Whether exec follows it across the SELinux label boundary is part of spike R1.
        val node = File(binDir, "node")
        val target = executable.absolutePath
        val current = try {
            Os.readlink(node.absolutePath)
        } catch (_: ErrnoException) {
            null
        }
        if (current == target) return
        if (current != null || node.exists()) node.delete()
        try {
            Os.symlink(target, node.absolutePath)
        } catch (e: ErrnoException) {
            throw IOException("could not link $node to $target", e)
        }
    }

    /**
     * [ensureLayout], then unpacks the npm this APK carries into `files/npm` unless that exact
     * npm is already there. Blocking (about 2000 files the first time, then a file read): never
     * on the main thread.
     *
     * The unpacked tree is stamped with npm's version and the SHA-256 of its tarball, so an app
     * update that moves npm replaces it and one that does not costs nothing. It is unpacked
     * beside `files/npm` and swapped in by two renames, the old tree deleted only afterwards, so
     * a process killed at any point leaves a whole npm or none at `files/npm`, never a mixture;
     * a missing stamp means "unpack again".
     *
     * One unpack at a time in the process ([LAYOUT_LOCK]): every NodeRuntime shares
     * `files/npm.new` and `files/npm.old`, and two unpacks interleaved there could swap a
     * stamped but half-written tree into place, which the stamp would then keep for good.
     */
    @Throws(IOException::class)
    fun ensureExtracted(): Unit = synchronized(LAYOUT_LOCK) {
        ensureLayout()
        val stamp = "npm ${BuildConfig.NPM_VERSION} ${BuildConfig.NPM_SHA256}\n"
        val marker = File(npmDir, STAMP)
        if (marker.isFile && marker.readText() == stamp) return
        val staging = File(files, "npm.new")
        val old = File(files, "npm.old")
        staging.deleteRecursively()
        old.deleteRecursively()
        app.assets.open(NPM_ASSET).use { Tar.extractTgz(it, staging, stripComponents = 1) }
        File(staging, STAMP).writeText(stamp)
        if (npmDir.exists() && !npmDir.renameTo(old)) throw IOException("could not move $npmDir aside")
        if (!staging.renameTo(npmDir)) throw IOException("could not move $staging to $npmDir")
        old.deleteRecursively()
    }

    /**
     * The environment every Node process gets. The heap cap is sized from the phone's RAM
     * because the default is a desktop's; a 3 GB tablet should not let V8 grow past 512 MB.
     */
    fun env(): Map<String, String> {
        val totalMb = memoryMb()
        val heapMb = (totalMb / 4).coerceIn(256L, 1536L)
        return linkedMapOf(
            "HOME" to home.absolutePath,
            "TMPDIR" to tmp.absolutePath,
            "PATH" to "${binDir.absolutePath}:/system/bin",
            "LD_LIBRARY_PATH" to nativeLibraryDir.absolutePath,
            "SIGNALK_NODE_CONFIG_DIR" to configDir.absolutePath,
            "NODE_OPTIONS" to "--max-old-space-size=$heapMb",
            "npm_config_cache" to File(home, ".npm").absolutePath,
            "npm_config_prefix" to current.absolutePath,
            "npm_config_update_notifier" to "false",
            "npm_config_fund" to "false",
            "npm_config_audit" to "false",
        )
    }

    /**
     * Starts `node args...` in [cwd] with [env], stderr merged into stdout, as the leader of a
     * process group of its own ([NodeProcess]). The caller owns it: reads its output on its own
     * thread, waits for it, stops it with [NodeProcess.terminate] or [NodeProcess.kill] (never
     * through [Process.destroy], which would leave whatever it started running) and calls
     * [NodeProcess.reap] once it has ended. Throws [java.io.IOException] when the executable
     * cannot be run, which on Android reads as "Permission denied" (EACCES) rather than
     * "not found" when SELinux refuses the exec.
     */
    fun spawn(vararg args: String, cwd: File = home): NodeProcess {
        ensureLayout()
        val groupFile = File(groups, "${System.nanoTime()}-${SPAWNS.incrementAndGet()}")
        val pb = ProcessBuilder(listOf(executable.absolutePath) + args)
            .directory(cwd)
            .redirectErrorStream(true)
        // Only here, not in env(): the launcher removes it before Node starts, so the nodes this
        // one runs join its group rather than leading their own.
        pb.environment().apply { clear(); putAll(env()); put(GROUP_FILE_ENV, groupFile.absolutePath) }
        return NodeProcess(pb.start(), groupFile)
    }

    /**
     * Starts `npm args...` the way the npm shell wrapper would on a Pi, as node running
     * npm-cli.js. [ensureExtracted] must have run; [spawn]'s rules apply.
     */
    fun npm(vararg args: String, cwd: File = home): NodeProcess = spawn(npmCli.absolutePath, *args, cwd = cwd)

    /** A one-paragraph description of the runtime situation, for the spike screen and diagnostics. */
    fun describe(): String = buildString {
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("abi: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("page size: ${pageSize()} bytes")
        appendLine("nativeLibraryDir: $nativeLibraryDir")
        appendLine("executable: $executable (${if (isAvailable) "present" else "absent"})")
        appendLine("npm: ${BuildConfig.NPM_VERSION} (${if (File(npmDir, STAMP).isFile) "unpacked" else "not unpacked yet"})")
        appendLine("ram: ${memoryMb()} MB")
        appendLine()
        appendLine("environment:")
        for ((k, v) in env()) appendLine("  $k=$v")
    }

    private fun memoryMb(): Long {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / (1024 * 1024)
    }

    private fun pageSize(): Long = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)

    companion object {
        /** Named like a library so the build packages it in lib/<abi>/ and the installer extracts it. */
        const val EXEC_NAME = "libnodeexec.so"

        /** npm's registry tarball, put into the APK's assets by this module's build. */
        const val NPM_ASSET = "npm.tgz"

        /** Written last into an unpacked npm: what was unpacked, so the next start can tell. */
        private const val STAMP = ".pocketsk-extracted"

        /**
         * Process-wide, not per instance: the activity and the service will each hold their own
         * NodeRuntime, and both lay out and unpack into the same directories.
         */
        private val LAYOUT_LOCK = Any()

        /** Whether this process has dropped the group records of the one before; under [LAYOUT_LOCK]. */
        private var groupsSwept = false

        /** Numbers each spawn's group record, so two in the same nanosecond do not share one. */
        private val SPAWNS = AtomicLong()

        /** Read and removed by the launcher (native/launcher/main.cc). */
        private const val GROUP_FILE_ENV = "POCKETSK_GROUP_FILE"
    }
}
