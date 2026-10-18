package fi.pocketsk.install

/**
 * Whether a freshly promoted server is healthy (docs/PLAN.md §5.2 step 6): it must answer
 * `GET /signalk` within [answerWithinMs] of promotion, and must not crash [crashLimit] times
 * within any [crashWindowMs]. Until [openMs] after promotion the verdict can still change; after
 * that a server that has answered is [Verdict.HEALTHY] for good. A [Verdict.FAILED] gate means
 * roll back and mark the version bad ([InstallManager.rollback]).
 *
 * The supervisor (§6) feeds it: [answered] from its health poll, [crashed] for every exit it did
 * not ask for. Pure, with the clock passed in, so every rule is unit-tested; times are
 * monotonic milliseconds.
 */
internal class HealthGate(
    private val promotedAt: Long,
    private val answerWithinMs: Long = ANSWER_WITHIN_MS,
    private val crashLimit: Int = CRASH_LIMIT,
    private val crashWindowMs: Long = CRASH_WINDOW_MS,
    private val openMs: Long = OPEN_MS,
) {
    enum class Verdict { PENDING, HEALTHY, FAILED }

    private var answered = false
    private val crashes = ArrayDeque<Long>()
    private var failed = false

    /** The server answered `GET /signalk` at [now]. */
    fun answered(now: Long) {
        if (now - promotedAt <= answerWithinMs) answered = true
    }

    /** The server exited without being asked to at [now]. */
    fun crashed(now: Long) {
        if (now - promotedAt > openMs) return
        crashes.addLast(now)
        while (crashes.isNotEmpty() && now - crashes.first() > crashWindowMs) crashes.removeFirst()
        if (crashes.size >= crashLimit) failed = true
    }

    /** The verdict at [now]: once [Verdict.FAILED] or [Verdict.HEALTHY] it stays so. */
    fun verdict(now: Long): Verdict = when {
        failed -> Verdict.FAILED
        !answered && now - promotedAt > answerWithinMs -> Verdict.FAILED.also { failed = true }
        answered && now - promotedAt > openMs -> Verdict.HEALTHY
        else -> Verdict.PENDING
    }

    companion object {
        const val ANSWER_WITHIN_MS = 90_000L
        const val CRASH_LIMIT = 3
        const val CRASH_WINDOW_MS = 5 * 60_000L

        /** As long as the crash window: a server that has run that long has shown it starts. */
        const val OPEN_MS = CRASH_WINDOW_MS
    }
}
