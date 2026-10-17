package fi.pocketsk.node

import android.content.Context
import android.os.Build
import java.io.File

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
 *     files/sk/slots/{A,B}  npm prefixes holding node_modules/signalk-server
 *     files/sk/current      symlink to the live slot
 *     files/npm             npm-cli.js and its node_modules, extracted from assets
 *     files/bin             node (symlink into nativeLibraryDir), npm and node-gyp wrappers
 *     files/prebuilds       cache of native addon builds fetched by the node-gyp shim
 *     cache/tmp             $TMPDIR
 *
 * Nothing here is created until [ensureLayout]. The executable comes from the `node-runtime`
 * module; on an ABI it does not build for, [executable] is absent and [isAvailable] false, which
 * is what the spike screen reports.
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

    /** Creates the directories and the `node` symlink. Idempotent. Cheap enough to call on every start. */
    fun ensureLayout() {
        for (d in listOf(home, configDir, slots, npmDir, binDir, prebuilds, tmp)) d.mkdirs()
        val node = File(binDir, "node")
        if (isAvailable && !node.exists()) {
            // A symlink into nativeLibraryDir, so `node` is on PATH for npm's lifecycle scripts.
            // Whether exec follows it across the SELinux label boundary is part of spike R1.
            android.system.Os.symlink(executable.absolutePath, node.absolutePath)
        }
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
     * Starts `node args...` in [cwd] with [env], stderr merged into stdout. The caller owns the
     * process: reads its output on its own thread, waits for it, kills it. Throws
     * [java.io.IOException] when the executable cannot be run, which on Android reads as
     * "Permission denied" (EACCES) rather than "not found" when SELinux refuses the exec.
     */
    fun spawn(vararg args: String, cwd: File = home): Process {
        ensureLayout()
        val pb = ProcessBuilder(listOf(executable.absolutePath) + args)
            .directory(cwd)
            .redirectErrorStream(true)
        pb.environment().apply { clear(); putAll(env()) }
        return pb.start()
    }

    /** A one-paragraph description of the runtime situation, for the spike screen and diagnostics. */
    fun describe(): String = buildString {
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("abi: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("page size: ${pageSize()} bytes")
        appendLine("nativeLibraryDir: $nativeLibraryDir")
        appendLine("executable: $executable (${if (isAvailable) "present" else "absent"})")
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
    }
}
