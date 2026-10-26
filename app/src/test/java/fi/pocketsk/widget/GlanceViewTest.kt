package fi.pocketsk.widget

import fi.pocketsk.bridge.BridgeProtocol.Glance
import fi.pocketsk.bridge.BridgeProtocol.Reading
import org.junit.Assert.assertEquals
import org.junit.Test

class GlanceViewTest {
    private val now = 1_760_000_000_000L

    private fun at(value: Double, ago: Long = 1000) = Reading(value, now - ago)

    @Test
    fun theReadingsAreInMetresKnotsAndDegreesOffTheBow() {
        val view = GlanceView.of(Glance(depth = at(3.24), sog = at(2.572), aws = at(5.144), awa = at(-0.61)), now)
        assertEquals(GlanceView("3.2", "5.0", "10", "35", GlanceView.Side.PORT), view)
        assertEquals(GlanceView.Side.STARBOARD, GlanceView.of(Glance(null, null, null, at(0.61)), now).windSide)
    }

    @Test
    fun deepWaterIsInWholeMetres() {
        assertEquals("99.9", GlanceView.of(Glance(at(99.94), null, null, null), now).depth)
        assertEquals("100", GlanceView.of(Glance(at(99.96), null, null, null), now).depth)
        assertEquals("1234", GlanceView.of(Glance(at(1234.4), null, null, null), now).depth)
    }

    @Test
    fun anAngleFrom0To2PiIsReadAsPortOrStarboard() {
        val view = GlanceView.of(Glance(null, null, null, at(2 * Math.PI - 0.61)), now)
        assertEquals("35", view.windAngle)
        assertEquals(GlanceView.Side.PORT, view.windSide)
    }

    @Test
    fun deadAheadAndAsternHaveNoSide() {
        assertEquals(GlanceView(null, null, null, "0", null), GlanceView.of(Glance(null, null, null, at(0.001)), now))
        assertEquals(null, GlanceView.of(Glance(null, null, null, at(Math.PI)), now).windSide)
        assertEquals("180", GlanceView.of(Glance(null, null, null, at(-Math.PI)), now).windAngle)
    }

    @Test
    fun aStaleReadingOrOneWithoutATimeIsNotShown() {
        val view = GlanceView.of(
            Glance(depth = at(3.0, ago = GlanceView.STALE_MS + 1), sog = Reading(2.0, null), aws = at(5.0, ago = GlanceView.STALE_MS), awa = null),
            now,
        )
        assertEquals(GlanceView(null, null, "10", null, null), view)
        // A time far in the future is a broken clock, not a fresh reading.
        assertEquals(null, GlanceView.of(Glance(at(3.0, ago = -GlanceView.STALE_MS - 1), null, null, null), now).depth)
    }

    @Test
    fun nothingIsShownOfAnEmptyGlance() {
        assertEquals(GlanceView.EMPTY, GlanceView.of(Glance.NONE, now))
    }

    @Test
    fun theNumbersUseAPointWhateverTheLanguage() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("fi-FI"))
            assertEquals("3.2", GlanceView.of(Glance(at(3.24), null, null, null), now).depth)
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }
}
