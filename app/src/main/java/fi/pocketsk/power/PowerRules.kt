package fi.pocketsk.power

import fi.pocketsk.bridge.BridgeProtocol

/**
 * The Dock's power profiles (docs/PLAN.md §8.1): what each takes from the phone. The server runs
 * in all three; what they change is what the phone itself spends on it.
 */
internal enum class PowerProfile {
    /** In harbour, on shore power: everything the plugin asks for, the screen as the owner set it. */
    HARBOR,

    /** Under way: everything still, and the screen dimmed, as the helm screen's Dim does it. */
    PASSAGE,

    /** On the last of the battery: the phone's sensors off but GNSS, at most once a second, and the battery's own state. */
    SAVER,
}

/**
 * What a [PowerProfile] makes of the plugin's subscription and of the screen. Pure, so the rules
 * are tested without a phone.
 */
internal object PowerRules {
    /** The most GNSS fixes a second in [PowerProfile.SAVER]. */
    const val SAVER_GNSS_HZ = 1.0

    /**
     * The sensors [PowerProfile.SAVER] keeps: GNSS, which a boat on a passage cannot do without,
     * and the battery, whose state Android broadcasts anyway, so reading it costs nothing.
     */
    val SAVER_SENSORS = setOf(BridgeProtocol.GNSS, BridgeProtocol.BATTERY)

    /**
     * The part of [asked] the phone serves under [profile]. Battery saver keeps [SAVER_SENSORS]
     * of those asked, GNSS at no more than [SAVER_GNSS_HZ], and stops the Bluetooth LE scan, a
     * radio listening all the time. The serial devices stay as asked: they are the boat's
     * instruments, not the phone's, and their data is what the server is for.
     */
    fun subscription(profile: PowerProfile, asked: BridgeProtocol.Subscription): BridgeProtocol.Subscription = when (profile) {
        PowerProfile.HARBOR, PowerProfile.PASSAGE -> asked
        PowerProfile.SAVER -> asked.copy(
            sensors = asked.sensors intersect SAVER_SENSORS,
            gnssHz = minOf(asked.gnssHz, SAVER_GNSS_HZ),
            ble = BridgeProtocol.Ble.NONE,
        )
    }

    /** Whether [profile] dims the screen, whatever the helm screen's own Dim says. */
    fun dims(profile: PowerProfile): Boolean = profile == PowerProfile.PASSAGE
}
