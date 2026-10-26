package fi.pocketsk.night

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightRulesTest {
    private fun overlay(settings: NightSettings) = NightRules.overlay(settings) ?: throw AssertionError("no overlay for $settings")

    @Test
    fun nothingOnIsNoOverlay() {
        assertNull(NightRules.overlay(NightSettings()))
    }

    @Test
    fun theFilterAloneTintsAndLeavesTheBrightnessAndTheScreenToThePhone() {
        val o = overlay(NightSettings(filter = true, strength = 60))
        assertEquals(0.48f, o.alpha, 1e-6f)
        assertEquals(NightRules.PHONE_BRIGHTNESS, o.brightness)
        assertFalse(o.keepOn)
    }

    @Test
    fun dimmingOrKeepingTheScreenOnAloneIsAClearOverlay() {
        val dim = overlay(NightSettings(dim = true, brightness = 10))
        assertEquals(0f, dim.alpha)
        assertEquals(0.1f, dim.brightness, 1e-6f)
        assertFalse(dim.keepOn)
        val on = overlay(NightSettings(keepOn = true))
        assertEquals(0f, on.alpha)
        assertEquals(NightRules.PHONE_BRIGHTNESS, on.brightness)
        assertTrue(on.keepOn)
    }

    @Test
    fun theyCombine() {
        val o = overlay(NightSettings(filter = true, strength = 100, dim = true, brightness = 5, keepOn = true))
        assertEquals(NightRules.MAX_ALPHA, o.alpha, 1e-6f)
        assertEquals(0.05f, o.brightness, 1e-6f)
        assertTrue(o.keepOn)
    }

    @Test
    fun theFilterNeverPassesTheOpacityAndroidLetsTouchesThrough() {
        assertEquals(NightRules.MAX_ALPHA, NightRules.alpha(250), 1e-6f)
        assertEquals(0f, NightRules.alpha(-5), 1e-6f)
        assertEquals(0.4f, NightRules.alpha(50), 1e-6f)
    }

    @Test
    fun theBrightnessIsNeverZero() {
        assertEquals(0.01f, NightRules.brightness(0), 1e-6f)
        assertEquals(0.01f, NightRules.brightness(-3), 1e-6f)
        assertEquals(1f, NightRules.brightness(400), 1e-6f)
    }
}
