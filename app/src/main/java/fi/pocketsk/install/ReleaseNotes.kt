package fi.pocketsk.install

import fi.pocketsk.bridge.Json
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The Updates card's changelog (docs/PLAN.md §8.1): signalk-server's release notes, taken from its
 * GitHub releases, for the versions a move from the live one to the one on offer brings, as plain
 * lines a phone shows without a Markdown renderer.
 *
 * Most of them GitHub writes itself: "## What's Changed", a line per pull request ending "by
 * @someone in https://github.com/…/pull/123", "## New Contributors" and "**Full Changelog**: …".
 * They are cut to what a boat owner reads, the changes, each with its pull request's number; a
 * release written by hand keeps its headings and its points.
 *
 * Pure, so the reading and the choice are unit-tested; [ReleaseNotesSource] fetches them.
 */
internal object ReleaseNotes {

    /** One release: its version, when it was published (epoch ms, null when GitHub gave none), its lines. */
    data class Release(val version: ServerVersion, val published: Long?, val lines: List<String>)

    /**
     * GitHub's answer to `GET /repos/SignalK/signalk-server/releases`, newest first. Drafts and
     * tags that are not versions are left out. Throws [Json.ParseException] when it is not a list.
     */
    fun parse(json: String): List<Release> {
        val list = Json.parse(json) as? List<*> ?: throw Json.ParseException("not a list of releases")
        return list.mapNotNull { item ->
            val r = item as? Map<*, *> ?: return@mapNotNull null
            if (r["draft"] == true) return@mapNotNull null
            val version = (r["tag_name"] as? String)?.let(ServerVersion::parse) ?: return@mapNotNull null
            Release(version, (r["published_at"] as? String)?.let(::instant), lines(r["body"] as? String ?: ""))
        }.sortedByDescending { it.version }
    }

    /**
     * What a move from [live] to [offered] brings: the releases after the one, up to and with the
     * other, newest first; betas only when [offered] is one. A live version that is not a version
     * (a sideloaded build) gets the offered release's notes alone.
     */
    fun between(releases: List<Release>, live: String?, offered: ServerVersion): List<Release> {
        val from = live?.let(ServerVersion::parse)
        return releases.filter {
            val v = it.version
            v <= offered && (v.isStable || !offered.isStable) && if (from == null) v.compareTo(offered) == 0 else v > from
        }
    }

    /** A release's Markdown as lines to show, GitHub's own boilerplate left out. */
    fun lines(body: String): List<String> {
        val out = ArrayList<String>()
        var skipping = false
        for (raw in body.replace(COMMENT, "").lines()) {
            val line = raw.trim()
            val heading = HEADING.matchEntire(line)
            if (heading != null) {
                val title = inline(heading.groupValues[1])
                val plain = title.replace('’', '\'')
                skipping = plain.equals(NEW_CONTRIBUTORS, ignoreCase = true)
                if (!skipping && !plain.equals(WHATS_CHANGED, ignoreCase = true) && title.isNotEmpty()) out += title
                continue
            }
            if (skipping || line.isEmpty() || line.startsWith(FULL_CHANGELOG)) continue
            val bullet = BULLET.matchEntire(line)
            val text = inline(bullet?.groupValues?.get(1) ?: line)
            if (text.isNotEmpty()) out += if (bullet != null) "• $text" else text
        }
        return out
    }

    /** One line's Markdown and GitHub links as plain text; a pull request is left as its number. */
    private fun inline(s: String): String = s
        .replace(CREDIT, " (#$1)")
        .replace(PULL, "#$1")
        .replace(IMAGE, "")
        .replace(LINK, "$1")
        .replace(TAG, "")
        .replace(MARKS, "")
        .replace(SPACES, " ")
        .trim()

    /** A publication time, or none when it is not a time or lies past what epoch milliseconds hold. */
    private fun instant(text: String): Long? = try {
        Instant.parse(text).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    } catch (_: ArithmeticException) {
        null
    }

    private const val WHATS_CHANGED = "What's Changed"
    private const val NEW_CONTRIBUTORS = "New Contributors"
    private const val FULL_CHANGELOG = "**Full Changelog**"

    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val HEADING = Regex("^#{1,6}\\s+(.*?)\\s*#*$")
    private val BULLET = Regex("^[*+-]\\s+(.*)$")
    /** GitHub's credit on a generated line: " by @someone in https://github.com/o/r/pull/123". */
    private val CREDIT = Regex("\\s+by @[A-Za-z0-9-]+(?:\\[bot\\])? in https://github\\.com/[^\\s/]+/[^\\s/]+/pull/(\\d+)")
    private val PULL = Regex("https://github\\.com/[^\\s/]+/[^\\s/]+/pull/(\\d+)")
    private val IMAGE = Regex("!\\[[^\\]]*\\]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]+)\\]\\([^)]*\\)")
    private val TAG = Regex("<[^>]+>")
    private val MARKS = Regex("\\*\\*|__|`")
    private val SPACES = Regex("\\s{2,}")
}
