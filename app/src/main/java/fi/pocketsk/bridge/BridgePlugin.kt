package fi.pocketsk.bridge

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import fi.pocketsk.node.NodeRuntime
import fi.pocketsk.node.Tar
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Puts the server's half of the bridge, `@pocketsk/android-bridge` (sk-plugin/android-bridge in
 * this repository), where signalk-server finds it, before every start (docs/PLAN.md §7.2). The
 * plugin has no dependencies and ships in the APK as a plain tar ([ASSET]), so no npm and no
 * network are involved:
 *
 *  - it is unpacked into `files/plugins/android-bridge`, again only when the asset's SHA-256 is
 *    not the one stamped there (a new APK);
 *  - `.signalk/node_modules/@pocketsk/android-bridge` is a symlink to it, which is where the
 *    server looks for plugins;
 *  - `.signalk/package.json` lists it as a `file:` dependency. Without that, the next install
 *    from the App Store (an `npm install` in `.signalk`) would prune it as extraneous;
 *  - its settings file in `.signalk/plugin-config-data` is written enabled, but only when there
 *    is none: what the user set in the admin UI, turning it off included, stays.
 */
internal object BridgePlugin {
    const val ASSET = "android-bridge.tar"
    const val PACKAGE = "@pocketsk/android-bridge"
    const val PLUGIN_ID = "pocketsk-android-bridge"
    private const val STAMP = ".pocketsk-asset-sha256"

    @Throws(IOException::class)
    fun install(context: Context, runtime: NodeRuntime) {
        val files = context.filesDir
        val home = File(files, "plugins/android-bridge")
        unpack(context, home)
        link(home, File(runtime.configDir, "node_modules/$PACKAGE"))
        declare(File(runtime.configDir, "package.json"), home)
        enableOnce(File(runtime.configDir, "plugin-config-data/$PLUGIN_ID.json"))
    }

    private fun unpack(context: Context, home: File) {
        val sha = context.assets.open(ASSET).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        val stamp = File(home, STAMP)
        if (stamp.isFile && stamp.readText() == sha) return
        val staging = File(home.parentFile, "android-bridge.new")
        staging.deleteRecursively()
        context.assets.open(ASSET).use { Tar.extract(it, staging) }
        File(staging, STAMP).writeText(sha)
        home.deleteRecursively()
        if (!staging.renameTo(home)) throw IOException("could not move $staging to $home")
    }

    private fun link(target: File, link: File) {
        link.parentFile?.mkdirs()
        val current = try {
            Os.readlink(link.absolutePath)
        } catch (_: ErrnoException) {
            null
        }
        if (current == target.absolutePath) return
        // A copy npm made, or a stale link: replaced by ours.
        if (link.isDirectory && current == null) link.deleteRecursively() else link.delete()
        try {
            Os.symlink(target.absolutePath, link.absolutePath)
        } catch (e: ErrnoException) {
            throw IOException("could not link $link to $target: ${e.message}", e)
        }
    }

    /** As npm writes it: relative to `.signalk`, `file:../../plugins/android-bridge`. */
    private fun declare(packageJson: File, home: File) {
        val spec = "file:" + packageJson.absoluteFile.parentFile!!.toPath().relativize(home.absoluteFile.toPath())
        val json = try {
            if (packageJson.isFile) JSONObject(packageJson.readText()) else JSONObject().put("name", "signalk-server-config").put("version", "0.0.1")
        } catch (e: JSONException) {
            throw IOException("$packageJson is not JSON: ${e.message}", e)
        }
        val deps = json.optJSONObject("dependencies") ?: JSONObject().also { json.put("dependencies", it) }
        if (deps.optString(PACKAGE) == spec) return
        deps.put(PACKAGE, spec)
        write(packageJson, json.toString(2))
    }

    private fun enableOnce(config: File) {
        if (config.exists()) return
        config.parentFile?.mkdirs()
        val json = JSONObject()
            .put("enabled", true)
            .put("enableLogging", false)
            .put("enableDebug", false)
            .put("configuration", JSONObject().put("battery", true).put("barometer", true).put("barometerSeconds", 10))
        write(config, json.toString(2))
    }

    /** Written beside and renamed over, so a crash never leaves the server half a file. */
    private fun write(file: File, text: String) {
        val part = File(file.parentFile, file.name + ".part")
        part.writeText(text + "\n")
        if (!part.renameTo(file)) throw IOException("could not write $file")
    }
}
