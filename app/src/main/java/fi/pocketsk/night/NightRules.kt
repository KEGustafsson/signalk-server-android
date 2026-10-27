package fi.pocketsk.night

/**
 * The helm screen's settings (docs/PLAN.md §8.3): the red night filter and how strong it is, the
 * screen dimmed below the phone's own lowest and how far, and the screen kept on. Each is the
 * owner's, on this phone, and they combine: a filter on a dimmed screen is what a cockpit at night
 * wants.
 */
internal data class NightSettings(
    val filter: Boolean = false,
    /** The filter's strength, 0 to 100. */
    val strength: Int = DEFAULT_STRENGTH,
    val dim: Boolean = false,
    /** The screen's brightness while dimmed, 1 to 100 (per cent of the panel's range). */
    val brightness: Int = DEFAULT_BRIGHTNESS,
    val keepOn: Boolean = false,
) {
    companion object {
        const val DEFAULT_STRENGTH = 60
        const val DEFAULT_BRIGHTNESS = 10
    }
}

/**
 * What [NightSettings] make of the one overlay window that carries them all ([NightFilter]). Pure,
 * so it is tested.
 */
internal object NightRules {
    /**
     * The most opaque the overlay may be. Android 12 and later let a touch through a window of
     * another kind only while that window is at most this opaque
     * (`InputManager.getMaximumObscuringOpacityForTouch`, 0.8 by default); a stronger filter would
     * leave the browser under it deaf to every tap.
     */
    const val MAX_ALPHA = 0.8f

    /**
     * The filter's colour, ARGB: a dark red, so the black of a chart at night stays nearly black
     * under it while what is lit keeps only its red. A bright red would light up the black.
     */
    const val COLOUR = 0xFF700000.toInt()

    /** No brightness of the window's own: the phone's setting rules. */
    const val PHONE_BRIGHTNESS = -1f

    /** The overlay window's opacity, brightness (0 to 1, or [PHONE_BRIGHTNESS]) and whether it holds the screen on. */
    data class Overlay(val alpha: Float, val brightness: Float, val keepOn: Boolean)

    /**
     * The overlay [settings] want, or null when they want none: nothing then stands over the
     * screen. [dimmed] is the power profile's dimming (Passage), which dims to the helm screen's
     * brightness whether or not its own Dim is on.
     */
    fun overlay(settings: NightSettings, dimmed: Boolean = false): Overlay? {
        val dim = settings.dim || dimmed
        if (!settings.filter && !dim && !settings.keepOn) return null
        return Overlay(
            alpha = if (settings.filter) alpha(settings.strength) else 0f,
            brightness = if (dim) brightness(settings.brightness) else PHONE_BRIGHTNESS,
            keepOn = settings.keepOn,
        )
    }

    /** The overlay's opacity for a strength of 0 to 100: none at 0, [MAX_ALPHA] at 100. */
    fun alpha(strength: Int): Float = strength.coerceIn(0, 100) / 100f * MAX_ALPHA

    /**
     * The window's brightness for a level of 1 to 100. Never 0: some phones take a brightness of
     * 0 for the display off.
     */
    fun brightness(level: Int): Float = level.coerceIn(1, 100) / 100f
}
