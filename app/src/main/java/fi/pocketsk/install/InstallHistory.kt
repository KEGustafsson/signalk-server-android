package fi.pocketsk.install

/**
 * What has been installed on this phone, and what happened to it: `files/sk/install-history.json`,
 * shown in the Dock's Updates card (docs/PLAN.md §5.2, §8.1). Its other job is to remember the
 * versions that failed their health gate, so the same broken release is not promoted again
 * unasked.
 *
 * Pure: the rules are unit-tested here, and [InstallManager] reads and writes the file.
 */
internal data class InstallHistory(val entries: List<Entry> = emptyList()) {

    enum class Outcome {
        /** Installed, passed the smoke test and made live. */
        PROMOTED,

        /** The install or the smoke test failed; the live slot was not touched. */
        FAILED,

        /** Taken back by hand ("Rollback to …"): nothing says the version is broken. */
        ROLLED_BACK,

        /** Taken back by the health gate after promotion: this version does not run here. */
        BAD,
    }

    /** One install or rollback. [at] is wall-clock milliseconds; [detail] is shown as it is. */
    data class Entry(
        val at: Long,
        val version: String,
        val slot: String,
        val source: String,
        val outcome: Outcome,
        val detail: String? = null,
    )

    /** True once the health gate has rolled [version] back on this phone. */
    fun isBad(version: String): Boolean = entries.any { it.version == version && it.outcome == Outcome.BAD }

    /** Newest first, as the Updates card lists them. */
    val newestFirst: List<Entry> get() = entries.sortedByDescending { it.at }

    /** This history with [entry] added, the oldest dropped beyond [MAX_ENTRIES]. */
    operator fun plus(entry: Entry): InstallHistory = InstallHistory((entries + entry).takeLast(MAX_ENTRIES))

    companion object {
        /**
         * Enough for a few years of upgrades; a bad version older than this may be offered
         * again, which is what anyone would expect of a release that old.
         */
        const val MAX_ENTRIES = 100
    }
}
