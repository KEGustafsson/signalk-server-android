package fi.pocketsk.install

import java.net.URI
import java.net.URISyntaxException

/**
 * The versions the Updates card's "Other versions" and the setup wizard offer (docs/PLAN.md §8.1,
 * §16 15): npm's versions newest first, releases alone unless the owner asks for the beta and test
 * releases too, each with what the owner should know before picking it: the latest release, the
 * one running, the one kept to roll back to, a beta or test release, one the health gate rolled
 * back on this phone. Also the web address of a build (`.tgz`) to install instead.
 *
 * Pure, so the rules are unit-tested.
 */
internal object VersionChoices {

    /** What the list says beside a version. */
    enum class Note {
        /** The newest release npm has: what a boat gets unless someone asks otherwise. */
        LATEST,

        /** The version running now: picking it reinstalls it. */
        INSTALLED,

        /** The version kept on the phone: the Updates card's rollback reaches it without a download. */
        KEPT,

        /** A beta or a test build (`2.36.0-beta.1`, `2.3.0-canboatv2.1`): for trying out. */
        PRERELEASE,

        /** The health gate rolled it back on this phone: installed again only when asked twice. */
        ROLLED_BACK,
    }

    data class Choice(val version: ServerVersion, val notes: Set<Note>)

    /**
     * [available] (any order, prereleases included) as the list shows it, newest first: the
     * releases, and the beta and test releases too with [prereleases].
     */
    fun list(
        available: List<ServerVersion>,
        prereleases: Boolean,
        live: String?,
        kept: String?,
        history: InstallHistory,
    ): List<Choice> {
        val latest = available.filter { it.isStable }.maxOrNull()
        return available.distinct().sortedDescending()
            .filter { prereleases || it.isStable }
            .map { v ->
                Choice(
                    v,
                    buildSet {
                        if (v == latest) add(Note.LATEST)
                        if (v.text == live) add(Note.INSTALLED)
                        if (v.text == kept && kept != live) add(Note.KEPT)
                        if (!v.isStable) add(Note.PRERELEASE)
                        if (history.isBad(v.text)) add(Note.ROLLED_BACK)
                    },
                )
            }
    }

    /** What the list picks until the owner picks another: the latest release, else the newest version. */
    fun default(choices: List<Choice>): Choice? = choices.firstOrNull { Note.LATEST in it.notes } ?: choices.firstOrNull()

    /**
     * [text] as a web address npm can install a package from, or null: `https://` with a host, as
     * a CI build or a release asset is published. Plain `http` is refused, since anyone between
     * the phone and the server could hand npm a package of their own.
     */
    fun webAddress(text: String): String? {
        val t = text.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val uri = try {
            URI(t)
        } catch (_: URISyntaxException) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrEmpty()) return null
        return t
    }

    /**
     * [url] as the install history and the install log may keep it: scheme, host and path, without
     * a user, a password, a query or a fragment, which a signed link to a CI build carries as its
     * credential and the diagnostics bundle would then share. npm is given the whole address.
     */
    fun forLog(url: String): String = try {
        val u = URI(url.trim())
        if (u.scheme == null || u.host.isNullOrEmpty()) WEB_ADDRESS else "${u.scheme}://${u.host}${if (u.port >= 0) ":${u.port}" else ""}${u.rawPath.orEmpty()}"
    } catch (_: URISyntaxException) {
        WEB_ADDRESS
    }

    private const val WEB_ADDRESS = "a web address"
}
