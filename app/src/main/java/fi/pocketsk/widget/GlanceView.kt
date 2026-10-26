package fi.pocketsk.widget

import fi.pocketsk.bridge.BridgeProtocol
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the home-screen widget shows of a [BridgeProtocol.Glance] at a moment: the numbers as
 * text in the units a crew reads (depth in metres, speeds in knots, the wind's angle in degrees
 * off the bow and its side), each null when there is nothing current to show. The words around
 * them are resources, so this is pure and tested.
 *
 * A reading older than [STALE_MS] is not shown, and neither is one whose time the server did not
 * say: a dead instrument keeps its last value in the server for ever, and a depth the widget
 * shows as current must be one.
 */
internal data class GlanceView(
    val depth: String?,
    val sog: String?,
    val windSpeed: String?,
    /** The apparent wind's angle off the bow, 0 to 180 degrees, as text. */
    val windAngle: String?,
    val windSide: Side?,
) {
    /** Which side the wind comes from; none dead ahead or astern. */
    enum class Side { PORT, STARBOARD }

    companion object {
        /** Older than this, a reading is not shown: ten seconds, five glances missed. */
        const val STALE_MS = 10_000L

        /** One knot, in m/s. */
        private const val KNOT = 1852.0 / 3600.0

        val EMPTY = GlanceView(null, null, null, null, null)

        /** [glance] as the widget shows it at [now] (ms). */
        fun of(glance: BridgeProtocol.Glance, now: Long): GlanceView {
            fun BridgeProtocol.Reading?.current(): Double? =
                this?.takeIf { r -> r.ts != null && now - r.ts in -STALE_MS..STALE_MS }?.value
            val awa = glance.awa.current()
            val angle = awa?.let { normalised(it) }
            val degrees = angle?.let { Math.toDegrees(abs(it)).roundToInt() }
            return GlanceView(
                depth = glance.depth.current()?.let(::depthText),
                sog = glance.sog.current()?.let { oneDecimal(it / KNOT) },
                windSpeed = glance.aws.current()?.let { (it / KNOT).roundToInt().toString() },
                windAngle = degrees?.toString(),
                windSide = when {
                    degrees == null || degrees == 0 || degrees == 180 -> null
                    angle < 0 -> Side.PORT
                    else -> Side.STARBOARD
                },
            )
        }

        /** Depth in metres: a tenth under 100 m, where a tenth matters, whole metres beyond. */
        private fun depthText(m: Double): String = if (m < 99.95) oneDecimal(m) else m.roundToInt().toString()

        /** One decimal, with a point whatever the phone's language: the widget's numbers are a display, not prose. */
        private fun oneDecimal(x: Double): String = String.format(Locale.ROOT, "%.1f", x)

        /** An angle in -π..π, the way Signal K gives the apparent wind (some sources give 0..2π). */
        private fun normalised(rad: Double): Double {
            var a = rad % (2 * Math.PI)
            if (a > Math.PI) a -= 2 * Math.PI
            if (a < -Math.PI) a += 2 * Math.PI
            return a
        }
    }
}
