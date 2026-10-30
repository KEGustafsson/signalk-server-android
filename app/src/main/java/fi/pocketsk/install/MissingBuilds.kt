package fi.pocketsk.install

import fi.pocketsk.bridge.Json
import java.io.File
import java.io.IOException
import java.net.URLEncoder

/**
 * The native addons installed without an Android build (docs/PLAN.md §16 12.4), for the Dock to
 * name and offer to request. npm's launcher records them when an install leaves one without its
 * addon (runtime-tools/npm/prebuilds.js, `missing.json` in [fi.pocketsk.node.NodeRuntime.prebuilds]),
 * by the tree each install went into: the App Store's log says so too, but it is gone once the
 * install is over, and the plugin that needs the addon only fails when the server loads it.
 *
 * The record says what an install found; the Dock shows what is still so. A package removed since,
 * installed again in another version, or given an addon (its own build, or one placed by a later
 * install now that the Release has it) is no longer missing ([stillMissing]).
 *
 * [parse] and [requestUrl] are pure and tested; [read] looks at the files.
 */
internal object MissingBuilds {

    /** A package without an Android build: its name and version, and where it is installed. */
    data class Missing(val name: String, val version: String, val dir: String)

    /**
     * The record's packages, each name and version once (one package can sit in several trees),
     * in the order recorded. A record of another format, or no record at all, has none; an entry
     * that is not a package is skipped.
     */
    fun parse(json: String): List<Missing> {
        val record = try {
            Json.parse(json) as? Map<*, *>
        } catch (_: Json.ParseException) {
            null
        } ?: return emptyList()
        if ((record["format"] as? Double) != FORMAT) return emptyList()
        val roots = record["roots"] as? Map<*, *> ?: return emptyList()
        return roots.values.flatMap { (it as? List<*>).orEmpty() }.mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val name = m["name"] as? String ?: return@mapNotNull null
            val version = m["version"] as? String ?: return@mapNotNull null
            val dir = m["dir"] as? String ?: return@mapNotNull null
            if (name.isBlank() || version.isBlank() || dir.isBlank()) null else Missing(name, version, dir)
        }
    }

    /**
     * Whether the package recorded as [m] is installed in [m]'s directory still, in that version,
     * without an addon: none in `build/Release`, which a placed build or the package's own build
     * fills, nor in `prebuilds/android-arm64`, where node-gyp-build looks too.
     */
    fun stillMissing(m: Missing): Boolean {
        val dir = File(m.dir)
        val version = try {
            (Json.parse(File(dir, "package.json").readText()) as? Map<*, *>)?.get("version")
        } catch (_: IOException) {
            null
        } catch (_: Json.ParseException) {
            null
        }
        if (version != m.version) return false
        fun hasAddon(d: File) = d.listFiles()?.any { it.isFile && it.name.endsWith(".node") } == true
        return !hasAddon(File(dir, "build/Release")) && !hasAddon(File(dir, "prebuilds/android-arm64"))
    }

    /**
     * The packages in the record [file] that are still without a build, each once; none when
     * there is no record. Reads files: off the main thread.
     */
    fun read(file: File): List<Missing> {
        val text = try {
            file.readText()
        } catch (_: IOException) {
            return emptyList()
        }
        return parse(text).filter(::stillMissing).distinctBy { it.name to it.version }
    }

    /**
     * A new issue on this repository asking for [m]'s Android build, its title and text filled in:
     * the prebuilds are built from `prebuilds/packages.json` here, and a line there is what adds a
     * package. [appVersion] and [runtime] (nodejs-mobile's version, which fixes the Node C++ API
     * ABI a build must load under) say which build would load.
     */
    fun requestUrl(m: Missing, appVersion: String, runtime: String): String {
        val title = "Android build for ${m.name}@${m.version}"
        val body = """
            |The App Store installed `${m.name}` ${m.version}, a native addon, and the `prebuilds` Release has no Android build of it yet, so the plugin that needs it does not load.
            |
            |Please add it to `prebuilds/packages.json`.
            |
            |- App: $appVersion
            |- Runtime: nodejs-mobile $runtime
            |- Plugin that needs it:
        """.trimMargin()
        return "$ISSUES_NEW?title=${encode(title)}&body=${encode(body)}"
    }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** The record's file name in the prebuilds directory, as prebuilds.js writes it. */
    const val FILE = "missing.json"

    private const val FORMAT = 1.0

    private const val ISSUES_NEW = "https://github.com/KEGustafsson/signalk-server-android/issues/new"
}
