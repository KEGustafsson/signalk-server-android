package fi.pocketsk.server

import fi.pocketsk.net.Backoff

/**
 * What the supervisor does when the server exits without being asked to (docs/PLAN.md §6).
 *
 * - Exit 0 is Signal K's own restart (the admin UI's Restart button, a plugin installed from the
 *   App Store): started again at once, and not a failure. Unless it came within [minRunMs] of the
 *   start, because a server that exits 0 straight away would otherwise be restarted in a tight
 *   loop for ever; that counts as a failure like any other.
 * - Any other exit is a failure: started again after [Backoff], 2 s doubling to 60 s, which a
 *   run that lasted [windowMs] resets (a server that ran that long was working, and its next
 *   crash is a new story, not the sixth in a row).
 * - [failureLimit] failures within any [windowMs]: give up, and the service says so and offers
 *   a rollback.
 *
 * Pure, with the clock passed in (monotonic milliseconds), so every rule is unit-tested.
 */
internal class RestartPolicy(
    firstMs: Long = FIRST_MS,
    maxMs: Long = MAX_MS,
    private val failureLimit: Int = FAILURE_LIMIT,
    private val windowMs: Long = WINDOW_MS,
    private val minRunMs: Long = MIN_RUN_MS,
) {
    sealed interface Decision {
        /** Start again after [delayMs]; 0 is at once. */
        data class Restart(val delayMs: Long) : Decision

        /** Too many failures: stay stopped until someone starts it again. */
        data object GiveUp : Decision
    }

    private val backoff = Backoff(firstMs, maxMs)
    private val failures = ArrayDeque<Long>()

    /** The run that started at [startedAt] ended with [code] at [now], unasked. */
    fun exited(code: Int, startedAt: Long, now: Long): Decision {
        val ran = now - startedAt
        if (code == 0 && ran >= minRunMs) return Decision.Restart(0)
        if (ran >= windowMs) backoff.reset()
        failures.addLast(now)
        while (failures.isNotEmpty() && now - failures.first() > windowMs) failures.removeFirst()
        if (failures.size >= failureLimit) return Decision.GiveUp
        return Decision.Restart(backoff.next())
    }

    /** A fresh start: the user started it again, or another version is live. */
    fun reset() {
        backoff.reset()
        failures.clear()
    }

    companion object {
        const val FIRST_MS = 2_000L
        const val MAX_MS = 60_000L
        const val FAILURE_LIMIT = 5
        const val WINDOW_MS = 10 * 60_000L
        const val MIN_RUN_MS = 5_000L
    }
}
