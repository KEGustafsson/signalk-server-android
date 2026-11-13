package fi.pocketsk.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import fi.pocketsk.R

/**
 * The Dock's colours, the same deep sea blue on near-black as the window theme (values/colors.xml,
 * which says why), so the Compose screens and the runtime checks read as one app. Dark only, as
 * the window theme: a helm at night is the case it is drawn for. The layers (cards, the bottom
 * bar) are lifted from the surface in that blue rather than Material's default grey-violet.
 */
@Composable
internal fun PocketTheme(content: @Composable () -> Unit) {
    val surface = colorResource(R.color.surface)
    val onSurface = colorResource(R.color.on_surface)
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = colorResource(R.color.primary),
            onPrimary = colorResource(R.color.on_primary),
            secondaryContainer = colorResource(R.color.secondary_container),
            onSecondaryContainer = colorResource(R.color.on_secondary_container),
            background = surface,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            onSurfaceVariant = colorResource(R.color.on_surface_variant),
            surfaceContainerLowest = colorResource(R.color.surface_lowest),
            surfaceContainerLow = colorResource(R.color.surface_low),
            surfaceContainer = colorResource(R.color.surface_container),
            surfaceContainerHigh = colorResource(R.color.surface_high),
            surfaceContainerHighest = colorResource(R.color.surface_highest),
            outline = colorResource(R.color.outline),
        ),
        content = content,
    )
}

/** The Home ring's colour for the server's state: running, on its way, stopped, failed. */
internal enum class Tone { RUNNING, BUSY, STOPPED, FAILED }

@Composable
internal fun Tone.color(): Color = when (this) {
    Tone.RUNNING -> colorResource(R.color.state_running)
    Tone.BUSY -> colorResource(R.color.state_busy)
    Tone.STOPPED -> colorResource(R.color.state_stopped)
    Tone.FAILED -> MaterialTheme.colorScheme.error
}
