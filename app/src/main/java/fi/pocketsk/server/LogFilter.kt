package fi.pocketsk.server

/**
 * The Dock's Logs card's filter (docs/PLAN.md §8.1, Logs): which of the server's latest lines it
 * shows. A [Kind] narrows to the problems or to the app's own `[pocketsk]` lines, and the words
 * typed narrow further; a line is shown when it holds every word, in any case and any order,
 * because what a person remembers of a line is a word or two out of it, not where they stood.
 *
 * Pure, so the choice is unit-tested; [ServerLog] holds the lines.
 */
internal object LogFilter {

    enum class Kind {
        /** Every line. */
        ALL,

        /** Errors, warnings and failures, in the words Node, npm and Signal K's plugins print them. */
        PROBLEMS,

        /** The supervisor's own lines ([ServerLog.note]). */
        APP,
    }

    /** The words of [query], lower case; none for a blank one. */
    fun words(query: String): List<String> = query.trim().lowercase().split(SPACE).filter { it.isNotEmpty() }

    /** True when [line] is of [kind] and holds every one of [words] ([words] lower case). */
    fun matches(line: String, kind: Kind, words: List<String>): Boolean {
        val kept = when (kind) {
            Kind.ALL -> true
            Kind.PROBLEMS -> PROBLEM.containsMatchIn(line)
            Kind.APP -> line.contains(APP_MARK)
        }
        if (!kept) return false
        if (words.isEmpty()) return true
        val lower = line.lowercase()
        return words.all { lower.contains(it) }
    }

    /** The newest [max] of [lines] (oldest first) that match, oldest first. */
    fun select(lines: List<String>, kind: Kind, query: String, max: Int): List<String> {
        val words = words(query)
        if (kind == Kind.ALL && words.isEmpty()) return lines.takeLast(max)
        val out = ArrayDeque<String>()
        for (i in lines.indices.reversed()) {
            if (out.size >= max) break
            if (matches(lines[i], kind, words)) out.addFirst(lines[i])
        }
        return out
    }

    private const val APP_MARK = "[pocketsk]"
    private val SPACE = Regex("\\s+")

    /**
     * What a problem line says: Node's `Error:`/`TypeError` and stack frames' `Exception`, npm's
     * `ERR!` and `WARN`, Signal K's and its plugins' `error`, `warn`, `failed`, and a process that
     * ended badly (`fatal`, `crash`, `uncaught`, `denied`). "error" counts anywhere in a word,
     * because Node names its errors `TypeError`, `RangeError` and so on; the rest only at a word's
     * start, so a "warning" is one and a "forewarned" is not.
     */
    private val PROBLEM = Regex(
        "(?i)(error|\\berr!|\\bwarn|\\bfail|\\bfatal|\\bcrash|\\buncaught|\\bdenied|\\bexception|\\bunhandled)",
    )
}
