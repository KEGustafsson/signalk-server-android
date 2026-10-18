package fi.pocketsk.install

/**
 * A signalk-server version as npm publishes it, `MAJOR.MINOR.PATCH[-PRERELEASE][+BUILD]`, ordered
 * the way semver orders it, so the installer can offer the newest stable release first and keep
 * betas out of the default (docs/PLAN.md §5.2, §5.3). Pure, so the order is unit-tested.
 *
 * Only the parts signalk-server's own versions use are handled: numeric cores, and prerelease
 * identifiers compared numerically when both are numbers and as text otherwise, a shorter
 * prerelease sorting first when it is a prefix of the other. Build metadata is kept for display
 * and ignored in the order, as semver says.
 */
internal data class ServerVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: List<String> = emptyList(),
    val text: String,
) : Comparable<ServerVersion> {

    /** A release, not a beta or release candidate: what a boat gets unless someone asks otherwise. */
    val isStable: Boolean get() = prerelease.isEmpty()

    override fun compareTo(other: ServerVersion): Int {
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }).let { if (it != 0) return it }
        // A release sorts after every prerelease of itself.
        if (prerelease.isEmpty() != other.prerelease.isEmpty()) return if (prerelease.isEmpty()) 1 else -1
        for (i in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val c = compareIdentifier(prerelease[i], other.prerelease[i])
            if (c != 0) return c
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    override fun toString(): String = text

    companion object {
        private val PATTERN = Regex("""^v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        /** Null for anything npm would not call a version. */
        fun parse(text: String): ServerVersion? {
            val m = PATTERN.matchEntire(text.trim()) ?: return null
            val (major, minor, patch, pre) = m.destructured
            val ids = if (pre.isEmpty()) emptyList() else pre.split('.')
            if (ids.any { it.isEmpty() }) return null
            return ServerVersion(
                major.toIntOrNull() ?: return null,
                minor.toIntOrNull() ?: return null,
                patch.toIntOrNull() ?: return null,
                ids,
                text.trim(),
            )
        }

        /**
         * What `npm view signalk-server versions --json` lists, parsed, newest first; betas only
         * when [withPrereleases]. Anything unparseable is dropped rather than failing the list.
         */
        fun newestFirst(texts: List<String>, withPrereleases: Boolean = false): List<ServerVersion> =
            texts.mapNotNull(::parse).filter { withPrereleases || it.isStable }.distinct().sortedDescending()

        private fun compareIdentifier(a: String, b: String): Int {
            val x = a.toLongOrNull()
            val y = b.toLongOrNull()
            return when {
                x != null && y != null -> x.compareTo(y)
                // Numeric identifiers sort before alphanumeric ones.
                x != null -> -1
                y != null -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
