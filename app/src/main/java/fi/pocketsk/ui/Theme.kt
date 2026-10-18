package fi.pocketsk.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import fi.pocketsk.R

/**
 * The Dock's colours, the same deep sea blue on near-black as the window theme (values/colors.xml,
 * which says why), so the Compose screens and the runtime checks read as one app. Dark only, as
 * the window theme: a helm at night is the case it is drawn for.
 */
@Composable
internal fun PocketTheme(content: @Composable () -> Unit) {
    val surface = colorResource(R.color.surface)
    val onSurface = colorResource(R.color.on_surface)
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = colorResource(R.color.primary),
            onPrimary = colorResource(R.color.on_primary),
            background = surface,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
        ),
        content = content,
    )
}
