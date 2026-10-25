package fi.pocketsk.server

/**
 * The server's latest output in memory, at most [maxChars] characters of it (docs/PLAN.md §6's
 * 2 MB; Signal K's output is ASCII but for the odd name, so characters and bytes are much the
 * same): what the screens show and what a diagnostics bundle would take. Bounded by size rather
 * than by lines because one plugin's debug output can be a hundred times longer per line than
 * another's. A line longer than the whole ring is cut to fit.
 *
 * Thread-safe: the server's reader adds while a screen reads.
 */
internal class LogRing(private val maxChars: Int = MAX_CHARS) {
    private val lines = ArrayDeque<String>()
    private var chars = 0

    @Synchronized
    fun add(line: String) {
        val l = if (line.length > maxChars - 1) line.substring(0, maxChars - 1) else line
        lines.addLast(l)
        chars += l.length + 1
        while (chars > maxChars) chars -= lines.removeFirst().length + 1
    }

    /** The last [count] lines, oldest first; only those are copied, not the whole ring. */
    @Synchronized
    fun tail(count: Int): List<String> = lines.subList(maxOf(0, lines.size - count), lines.size).toList()

    /** Characters held, newlines included. */
    val size: Int @Synchronized get() = chars

    @Synchronized
    fun clear() {
        lines.clear()
        chars = 0
    }

    companion object {
        const val MAX_CHARS = 2 * 1024 * 1024
    }
}
