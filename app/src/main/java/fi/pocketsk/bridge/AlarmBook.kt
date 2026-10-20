package fi.pocketsk.bridge

import fi.pocketsk.bridge.BridgeProtocol.Alarm

/**
 * What the phone does about the boat's alarms: which to show, and whether to ring. Pure, so it is
 * unit-tested; [PhoneAlarm] calls it from its own thread and does what it says.
 *
 * The plugin's list is the truth (`alarms` lines, [BridgeProtocol.Incoming.Alarms]): an alarm on
 * it is shown, and rings while the server still wants it heard ([Alarm.sound]). The owner's
 * Silence quiets the phone at once ([silence]), without waiting for the server's answer, and the
 * phone stays quiet for that alarm until it gets worse (an alarm that becomes an emergency) or
 * goes and comes back; so a Silence the server could not take (no notification API, an emergency
 * it refused) still quiets the phone, and an escalation is never missed for it.
 */
internal class AlarmBook {
    /**
     * What to show: the [alarms], the most urgent first, whether to [ring], and the ids of those
     * silenced on the phone ([quiet]).
     */
    data class View(val alarms: List<Alarm>, val ring: Boolean, val quiet: Set<String> = emptySet()) {
        /** The one on the screen and in the notification: the most urgent. */
        val head: Alarm? get() = alarms.firstOrNull()

        companion object {
            val NONE = View(emptyList(), false)
        }
    }

    private var alarms: List<Alarm> = emptyList()

    /** The alarms silenced on the phone, by id, with the state each was silenced in. */
    private val quiet = HashMap<String, Int>()

    /** The plugin's list, now. */
    fun update(list: List<Alarm>): View {
        // Stable: alarms as urgent as each other stay in the plugin's order.
        alarms = list.sortedByDescending { it.rank }
        val now = list.associateBy { it.id }
        // Gone, or worse than when it was silenced: it rings again.
        quiet.entries.removeAll { (id, rank) -> now[id]?.let { it.rank > rank } ?: true }
        return view()
    }

    /** The owner's Silence for [id]; the alarm silenced, or null when there is no such alarm now. */
    fun silence(id: String): Alarm? {
        val alarm = alarms.firstOrNull { it.id == id } ?: return null
        quiet[id] = alarm.rank
        return alarm
    }

    /** Every alarm forgotten: the server stopped. */
    fun clear(): View {
        alarms = emptyList()
        quiet.clear()
        return view()
    }

    fun view(): View = View(alarms, alarms.any { it.sound && it.id !in quiet }, quiet.keys.toSet())
}
