package fi.pocketsk.signalk

import fi.pocketsk.bridge.Json

/**
 * The server's webapps as the Dock's Webapps card and the home-screen shortcuts know them
 * (docs/PLAN.md §8.3): the npm packages that say they are one, by the keyword Signal K looks
 * for (`signalk-webapp`), each served by the server at `/<package name>/`. Read from the
 * packages' own `package.json`, not asked of the running server, so the card lists them while
 * the server is stopped too and needs no login when security is on.
 *
 * `signalk-embeddable-webapp` alone is left out: those are pieces the admin UI shows inside its
 * own pages, with nothing of their own to open full screen.
 *
 * Pure, so the reading, the name check and the link are unit-tested.
 */
internal object Webapps {

    /**
     * One webapp: [name] is the npm package (`@signalk/freeboard-sk`), [title] what to call it
     * (`signalk.displayName`, else the name), [icon] its icon's path inside the package when it
     * has one that stays inside it.
     */
    data class Webapp(val name: String, val title: String, val icon: String?)

    /** The deep link a shortcut opens: `pocketsk://webapp/<name>`. */
    const val SCHEME = "pocketsk"
    const val HOST = "webapp"

    /** A `package.json`'s webapp, or null when it is not one, not JSON or not a valid name. */
    fun parse(json: String): Webapp? {
        val p = try {
            Json.parse(json) as? Map<*, *>
        } catch (_: Json.ParseException) {
            null
        } ?: return null
        val name = (p["name"] as? String)?.takeIf(::validName) ?: return null
        val keywords = (p["keywords"] as? List<*>).orEmpty()
        if (WEBAPP !in keywords) return null
        val signalk = p["signalk"] as? Map<*, *>
        val title = (signalk?.get("displayName") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: name
        val icon = (signalk?.get("appIcon") as? String)?.let(::insidePath)
        return Webapp(name, title, icon)
    }

    /**
     * Whether a `package.json`'s [text] can be a webapp's, without parsing it: true when it names
     * the keyword or holds a `\u` escape, which could spell the keyword (the server parses the
     * file, so `signalk\u002dwebapp` is a webapp to it). A package tree holds hundreds of
     * packages and a handful of webapps, so only those few, and the rare file with an escape, are
     * parsed on every look.
     */
    fun mayBeWebapp(text: String): Boolean = text.contains(WEBAPP) || text.contains("\\u")

    /**
     * True for an npm package name, scoped or not, in the lower case npm has required since
     * 2017: the only names a shortcut or a deep link may carry, since the name becomes the URL's
     * path and nothing else may ride in with it.
     */
    fun validName(name: String): Boolean = name.length <= 214 && NAME.matches(name)

    /** The deep link for [name]. */
    fun link(name: String): String = "$SCHEME://$HOST/$name"

    /** The name a deep link's [host] and [path] carry, or null when they are not a webapp's. */
    fun nameOf(host: String?, path: String?): String? {
        if (host != HOST) return null
        val name = path?.trim('/') ?: return null
        return name.takeIf(::validName)
    }

    /** Where the server serves [name] (`/<name>/`), on its plain HTTP [base] (`http://127.0.0.1:3000`). */
    fun url(base: String, name: String): String = "$base/$name/"

    /**
     * The power of two an icon of [width] by [height] is decoded at (BitmapFactory's
     * `inSampleSize`): the largest that still leaves its longest side at [target] or more, so
     * the decoder never holds much more than a launcher icon's pixels and the last step down is a
     * scale of a bitmap already small.
     */
    fun sampleSize(width: Int, height: Int, target: Int): Int {
        val longest = maxOf(width, height)
        var size = 1
        while (longest / (size * 2) >= target) size *= 2
        return size
    }

    /** [path] with a leading `./` taken off, or null when it is absolute or climbs out with `..`. */
    private fun insidePath(path: String): String? {
        val p = path.trim().removePrefix("./")
        if (p.isEmpty() || p.startsWith("/") || p.split('/', '\\').any { it == ".." }) return null
        return p
    }

    private const val WEBAPP = "signalk-webapp"
    // Each hyphen last in its class, so no engine can read it as a range (`9-~` would take in A-Z).
    private val NAME = Regex("^(?:@[a-z0-9~-][a-z0-9._~-]*/)?[a-z0-9~-][a-z0-9._~-]*$")
}
