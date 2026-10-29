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
 *     files/bin             node and npm, symlinks to the executable
 *     files/runtime         the preloads every node loads ([preloads], from runtime-tools/preload)
 *                           and npm's launcher ([npmLauncher], from runtime-tools/npm)
 *     files/prebuilds       the native addon builds fetched after npm installs, and their index
 *     cache/tmp             $TMPDIR
 *     no_backup/groups      one file per running [spawn], the id of its process group ([NodeProcess])
 *
 * Nothing here is created until [ensureLayout], and npm is not unpacked until [ensureExtracted].
 * The executable comes from this module's native build; on an ABI it does not build for,
 * [executable] is absent and [isAvailable] false, which is what the spike screen reports.
 *
 * `files/bin/npm` is not npm's shell wrapper script. An app that targets API 29 or later may not
 * exec() a file in its own data directory, so a script there could be run only as
 * `sh files/bin/npm`, never by name, and Signal K Server runs npm by name (`npm --version`, its
 * App Store's installs). It is a second link to the executable, which runs [npmLauncher]
 * ([NPM_CLI_ENV]) when started under that name; the app's own [npm] runs it too. The launcher
 * runs `files/npm/bin/npm-cli.js` and, after an install, puts the Android builds of native
 * addons in place from the Release the Prebuilds workflow publishes (docs/PLAN.md §9.2), which
 * nothing else would: Signal K's App Store installs with `--ignore-scripts`. Whether npm's
 * lifecycle scripts get by under the exec rule is spike R3 (docs/PLAN.md §3, §15).
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
    val runtimeDir = File(files, "runtime")
    val tmp = File(cache, "tmp")

    /**
     * runtime-tools/preload's files, which every node this runtime starts loads before its own
     * code ([nodeOptions]), in [PRELOAD_ASSETS]' order: loopback.js, which keeps a process
     * started with [LOOPBACK_ENV] from listening beyond the phone and does nothing otherwise;
     * mdns-lookup.js, since Android has no resolver for `.local` names in native code and Signal K
     * connects to other servers by the names their mDNS advertisements give; and node-ble.js, the
     * Bluetooth LE that plugins written for BlueZ expect, fed by the phone's own scanner. The APK
     * carries them as assets; [ensureLayout] puts them here.
     */
    val preloads: List<File> = PRELOAD_ASSETS.map { File(runtimeDir, it) }

    /** loopback.js among the [preloads], which [spawn] insists on for a process given [LOOPBACK_ENV]. */
    private val loopbackPreload = File(runtimeDir, LOOPBACK_ASSET)

    /**
     * runtime-tools/npm's npm-launch.js, which every npm on the phone runs instead of npm-cli.js
     * itself (see the class comment), with prebuilds.js beside it ([NPM_TOOL_ASSETS]). Put in
     * place by [ensureLayout] like the [preloads]; an APK without it runs npm-cli.js directly.
     */
    val npmLauncher = File(runtimeDir, NPM_TOOL_ASSETS.first())

    /**
     * Where the launcher records each spawned node's process group; see [NodeProcess]. Not in the
     * cache: Android may clear that while the app runs, and a server's record is first read when
     * it is stopped, hours later perhaps; a lost record leaves the group unreachable. no_backup is
     * never cleared by the system and never backed up, which pids have no business in either.
     */
    val groups = File(app.noBackupFilesDir, "groups")

    /** signalk-server's own start script in [slot], where a global install puts it (PLAN §5.1). */
    fun serverMain(slot: File): File = File(slot, "lib/node_modules/signalk-server/bin/signalk-server")

    /** npm's entry point, once [ensureExtracted] has unpacked it. */
    val npmCli = File(npmDir, "bin/npm-cli.js")

    /**
     * Creates the directories, points `files/bin/node` and `files/bin/npm` at this install's
     * executable and puts the APK's [preloads] and [npmLauncher] in place. Idempotent, and cheap
     * enough to call on every start.
     *
     * The links are checked every time, not only created once: nativeLibraryDir is a new random
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
        for (d in listOf(home, configDir, slots, npmDir, binDir, prebuilds, runtimeDir, tmp, groups)) d.mkdirs()
        // Group records left by an earlier app process: nothing in them is alive any more, since
        // Android ends an app's processes, its children's too, with the app. Their ids are pids
        // that may since belong to someone else, so they are dropped once, before any spawn here.
        if (!groupsSwept) {
            groups.listFiles()?.forEach { it.delete() }
            groupsSwept = true
        }
        installRuntimeAssets()
        if (!isAvailable) return
        // Symlinks into nativeLibraryDir: `node` is on PATH for npm's lifecycle scripts, `npm`
        // for Signal K Server, which runs it by name. Whether exec follows them across the
        // SELinux label boundary is part of spike R1.
        linkExecutable(File(binDir, "node"))
        linkExecutable(File(binDir, "npm"))
    }

    /**
     * Writes each of the APK's [PRELOAD_ASSETS] and [NPM_TOOL_ASSETS] to its file in [runtimeDir]
     * unless the file holds it already; under [LAYOUT_LOCK]. Beside it first and then renamed, so
     * a node starting meanwhile reads the old file or the new one, never half of one. An APK
     * without an asset (a build that lost it) writes nothing for it, and [env] then leaves that
     * preload out, or runs npm-cli.js without the launcher, rather than name a file that is not
     * there, which would stop every node, or every npm, from starting.
     */
    private fun installRuntimeAssets() {
        for (name in PRELOAD_ASSETS + NPM_TOOL_ASSETS) {
            val file = File(runtimeDir, name)
            val bytes = try {
                app.assets.open(name).use { it.readBytes() }
            } catch (_: IOException) {
                continue
            }
            if (file.isFile && file.readBytes().contentEquals(bytes)) continue
            val staging = File(runtimeDir, "$name.new")
            staging.writeBytes(bytes)
            if (!staging.renameTo(file)) throw IOException("could not move $staging to $file")
        }
    }

    /** Points [link] at [executable] unless it already does; under [LAYOUT_LOCK]. */
    private fun linkExecutable(link: File) {
        val target = executable.absolutePath
        val current = try {
            Os.readlink(link.absolutePath)
        } catch (_: ErrnoException) {
            null
        }
        if (current == target) return
        if (current != null || link.exists()) link.delete()
        try {
            Os.symlink(target, link.absolutePath)
        } catch (e: ErrnoException) {
            throw IOException("could not link $link to $target", e)
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
     * [NODE_OPTIONS][nodeOptions] also loads the [preloads], those [ensureLayout] has put in place.
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
            "NODE_OPTIONS" to nodeOptions(heapMb, preloads.filter { it.isFile }),
            NPM_CLI_ENV to npmEntry().absolutePath,
            NPM_ENV to npmCli.absolutePath,
            PREBUILDS_ENV to prebuilds.absolutePath,
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
     * [NodeProcess.reap] once it has ended. [extraEnv] is laid over [env], for a run that needs
     * something else (the installer's smoke test: another config directory, other ports). Throws
     * [java.io.IOException] when the executable cannot be run, which on Android reads as
     * "Permission denied" (EACCES) rather than "not found" when SELinux refuses the exec, and
     * when [extraEnv] asks for the loopback ([LOOPBACK_ENV]) but the node would not load
     * loopback.js (the file is not in place, or [extraEnv] sets a NODE_OPTIONS without it): the
     * process would listen on every interface while the Dock says only this phone reaches it.
     */
    fun spawn(vararg args: String, cwd: File = home, extraEnv: Map<String, String> = emptyMap()): NodeProcess {
        ensureLayout()
        val environment = env() + extraEnv
        if (environment[LOOPBACK_ENV] == LOOPBACK_ON &&
            !(loopbackPreload.isFile && requires(environment["NODE_OPTIONS"].orEmpty(), loopbackPreload))
        ) {
            throw IOException("$loopbackPreload would not be loaded, so the server could not be kept to this phone")
        }
        val groupFile = File(groups, "${System.nanoTime()}-${SPAWNS.incrementAndGet()}")
        val pb = ProcessBuilder(listOf(executable.absolutePath) + args)
            .directory(cwd)
            .redirectErrorStream(true)
        // Only here, not in env(): the launcher removes it before Node starts, so the nodes this
        // one runs join its group rather than leading their own.
        pb.environment().apply {
            clear()
            putAll(environment)
            put(GROUP_FILE_ENV, groupFile.absolutePath)
        }
        return NodeProcess(pb.start(), groupFile)
    }

    /**
     * Starts `npm args...` the way the npm shell wrapper would on a Pi, as node running
     * [npmLauncher], which runs npm-cli.js and puts the native addons' builds in place after an
     * install. [ensureExtracted] must have run; [spawn]'s rules apply.
     */
    fun npm(vararg args: String, cwd: File = home): NodeProcess = spawn(npmEntry().absolutePath, *args, cwd = cwd)

    /** What runs as npm: [npmLauncher], or npm-cli.js itself in an APK that lacks the launcher. */
    private fun npmEntry(): File = if (NPM_TOOL_ASSETS.all { File(runtimeDir, it).isFile }) npmLauncher else npmCli

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

        /** What `files/bin/npm` (the executable started under that name) runs: [npmLauncher]. */
        const val NPM_CLI_ENV = "POCKETSK_NPM_CLI"

        /** Where [npmLauncher] finds npm-cli.js. */
        const val NPM_ENV = "POCKETSK_NPM"

        /** Where [npmLauncher] keeps the builds and the index it fetched ([prebuilds]). */
        const val PREBUILDS_ENV = "POCKETSK_PREBUILDS"

        /** npm's registry tarball, put into the APK's assets by this module's build. */
        const val NPM_ASSET = "npm.tgz"

        /**
         * Set to [LOOPBACK_ON] in [spawn]'s extraEnv, a node and every node it starts listen on
         * 127.0.0.1 alone (runtime-tools/preload/loopback.js): the Dock's LAN access switch off.
         */
        const val LOOPBACK_ENV = "POCKETSK_LOOPBACK"
        const val LOOPBACK_ON = "1"

        private const val LOOPBACK_ASSET = "loopback.js"

        /** runtime-tools/preload's files, which this module's build adds to the APK's assets, in the order they load. */
        val PRELOAD_ASSETS = listOf(LOOPBACK_ASSET, "mdns-lookup.js", "node-ble.js")

        /** runtime-tools/npm's files, which this module's build adds to the APK's assets: the launcher first, then what it requires. */
        val NPM_TOOL_ASSETS = listOf("npm-launch.js", "prebuilds.js")

        /**
         * `NODE_OPTIONS`: the heap cap, and each of [preloads] loaded with `--require`, in order.
         * Each path is quoted as NODE_OPTIONS reads it, the one form a space in it cannot split.
         */
        fun nodeOptions(heapMb: Long, preloads: List<File>): String = buildString {
            append("--max-old-space-size=").append(heapMb)
            for (p in preloads) append(" --require=\"").append(p.absolutePath).append('"')
        }

        /**
         * Whether [nodeOptions], read as Node reads `NODE_OPTIONS`, loads [preload]: one of its
         * arguments is `--require=<path>`, or `--require` or `-r` followed by the path. A mention of
         * the path anywhere else (another option's value) loads nothing, so it does not count.
         */
        fun requires(nodeOptions: String, preload: File): Boolean {
            val args = optionArgs(nodeOptions)
            val path = preload.absolutePath
            return args.indices.any { i ->
                args[i] == "--require=$path" || (args[i] in REQUIRE_FLAGS && args.getOrNull(i + 1) == path)
            }
        }

        /**
         * `NODE_OPTIONS` split into its arguments the way Node splits it: at whitespace outside
         * double quotes, the quotes removed, a backslash inside them taking the next character as
         * it is.
         */
        fun optionArgs(nodeOptions: String): List<String> {
            val args = mutableListOf<String>()
            val arg = StringBuilder()
            var quoted = false
            var any = false
            var i = 0
            while (i < nodeOptions.length) {
                val c = nodeOptions[i]
                when {
                    quoted && c == '\\' && i + 1 < nodeOptions.length -> arg.append(nodeOptions[++i])
                    c == '"' -> { quoted = !quoted; any = true }
                    !quoted && c.isWhitespace() -> {
                        if (any) args += arg.toString()
                        arg.clear()
                        any = false
                    }
                    else -> { arg.append(c); any = true }
                }
                i++
            }
            if (any) args += arg.toString()
            return args
        }

        private val REQUIRE_FLAGS = setOf("--require", "-r")

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
