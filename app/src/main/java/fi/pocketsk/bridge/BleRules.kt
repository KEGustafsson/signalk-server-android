package fi.pocketsk.bridge

/**
 * Android allows an app five Bluetooth LE scan starts in any 30 seconds, and fails the sixth
 * without a callback: the scan simply hears nothing (AppScanStats' "scanning too frequently").
 * [BleScanner] restarts its scans whenever the plugin asks for other devices, so it asks this
 * first: 0 when a start may go now, else how long to wait. Pure, with the clock passed in
 * (monotonic milliseconds), so it is unit-tested.
 */
internal class ScanBudget(private val starts: Int = MAX_STARTS, private val windowMs: Long = WINDOW_MS) {
    private val times = ArrayDeque<Long>()

    /** How long until a start is allowed at [now]; 0 when it is. */
    fun delayFor(now: Long): Long {
        while (times.isNotEmpty() && now - times.first() >= windowMs) times.removeFirst()
        return if (times.size < starts) 0 else times.first() + windowMs - now
    }

    /** A start was made at [now]. */
    fun started(now: Long) {
        times.addLast(now)
        while (times.size > starts) times.removeFirst()
    }

    companion object {
        /** One under Android's five, for the start a scan's own restart after an error may cost. */
        const val MAX_STARTS = 4
        const val WINDOW_MS = 30_000L
    }
}

/**
 * The same advertisement heard twice within [windowMs]: the second copy is dropped. A device the
 * plugin watches is heard both by the open scan and by the filtered one while the screen is on,
 * and some stacks report one packet more than once; BlueZ drops such copies too unless asked not
 * to. A device sending the same bytes again later is heard again. Pure, with the clock passed in.
 */
internal class Repeats(private val windowMs: Long = WINDOW_MS, private val max: Int = MAX) {
    private val last = LinkedHashMap<String, Pair<Int, Long>>()

    /** Whether the advertisement of [mac] with [hash] at [now] is new, and remembers it. */
    fun fresh(mac: String, hash: Int, now: Long): Boolean {
        val prev = last[mac]
        if (prev != null && prev.first == hash && now - prev.second < windowMs) return false
        last.remove(mac)
        last[mac] = hash to now
        if (last.size > max) last.remove(last.keys.first())
        return true
    }

    companion object {
        const val WINDOW_MS = 500L
        const val MAX = 1024
    }
}
