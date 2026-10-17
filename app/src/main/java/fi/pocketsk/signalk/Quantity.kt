package fi.pocketsk.signalk

/**
 * The part of Crew Radio's `ask/Quantities` that [SignalKTree] needs: the named instance a read
 * may ask for. (Ported from Crew Radio; [Role] is copied as-is.)
 *
 * In Crew Radio `Quantity` is the catalogue of what the crew can ask the boat by voice, and
 * [Role] is nested in it. Pocket SK has no such catalogue until "Ask boat data" arrives (PLAN
 * §17.2), but the home-screen widgets (§8.3) read the second engine or the start battery just
 * the same. So only [Role] comes across, under the same name, which keeps [SignalKTree] identical
 * in both repos (§17.4). When the catalogue arrives it replaces this object with Crew Radio's
 * data class, and every `Quantity.Role` reference stays as it is.
 */
object Quantity {

    /**
     * A named instance, so the user can reach the second of something.
     *
     * No two boats name their instances alike: `propulsion.port`, `propulsion.prt_engine`,
     * `propulsion.1`; `electrical.batteries.house` and `electrical.batteries.0`. So a role is
     * matched in three steps: the user's own line in Settings (`propulsion:starboard=1`), then
     * [matches] against the instance key, then [instanceNumber] against a key that is nothing but
     * a number, which is the NMEA 2000 convention (engine 0 is the port or only engine, engine 1
     * the starboard one). Battery and tank instances carry no such convention, so those roles
     * match by name only.
     *
     * [matches] reads the key as words rather than as one string, because the side is usually only
     * part of what the boat calls the thing: `prt_engine`, `stbEngine`, `Port Engine` all name a
     * side and a thing, and a whole-key comparison finds none of them.
     *
     * When none of the three finds it the answer is missing, never another instance. Reading the
     * port engine out as the starboard one is the same confident wrong answer that the staleness
     * gate exists to stop.
     */
    enum class Role(val names: List<String>, val instanceNumber: Int?) {
        PORT(listOf("port", "prt", "p", "portengine", "prtengine"), 0),
        STARBOARD(listOf("starboard", "stbd", "stb", "sb", "s", "starboardengine", "stbengine"), 1),
        HOUSE(listOf("house", "domestic", "service", "auxiliary", "aux"), null),
        START(listOf("start", "starter", "starting", "cranking", "engine"), null);

        /** How a Settings line names this role: the `starboard` of `propulsion:starboard=1`. */
        val key: String get() = name.lowercase()

        /** True when [instance] is this role, whole (`starboard`) or as one word of it (`stb_engine`). */
        fun matches(instance: String): Boolean =
            instance.lowercase().filter { it.isLetterOrDigit() } in names ||
                words(instance).any { it in names }

        companion object {
            /**
             * An instance key as the words a boat wrote into it: `prt_engine` and `stbEngine` and
             * `Port Engine` are all a side and a thing. Anything that is not a letter or a digit
             * separates, and so does the step from a lower-case letter into a capital, which is
             * how Signal K instance names are usually written.
             */
            fun words(key: String): List<String> =
                key.split(Regex("[^A-Za-z0-9]+|(?<=[a-z0-9])(?=[A-Z])"))
                    .filter { it.isNotEmpty() }
                    .map { it.lowercase() }
        }
    }
}
