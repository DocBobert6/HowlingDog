package app.howlingdog

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The HowlingDog theme: the icon's palette (deep maroon, gold,
 * dark charcoal) as the Material3 colour scheme. The sampled
 * values live in EraUi.Palette as ARGB ints so they are testable
 * on the plain JVM; this file only turns them into Compose
 * colours. The old default Material3 scheme (purple primary,
 * light surfaces) is gone everywhere — Settings, dialogs and
 * dropdowns all follow this scheme now.
 */
@Composable
fun HowlingDogTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(EraUi.Palette.GOLD),
            onPrimary = Color(EraUi.Palette.ON_GOLD),
            primaryContainer = Color(EraUi.Palette.MAROON),
            onPrimaryContainer = Color(EraUi.Palette.TEXT),
            secondary = Color(EraUi.Palette.GOLD_MUTED),
            onSecondary = Color(EraUi.Palette.ON_GOLD),
            background = Color(EraUi.Palette.BACKGROUND),
            onBackground = Color(EraUi.Palette.TEXT),
            surface = Color(EraUi.Palette.SURFACE),
            onSurface = Color(EraUi.Palette.TEXT),
            surfaceVariant = Color(EraUi.Palette.SURFACE_RAISED),
            onSurfaceVariant = Color(EraUi.Palette.TEXT_MUTED),
            error = Color(EraUi.Palette.WARNING),
            onError = Color(EraUi.Palette.ON_GOLD),
        ),
        content = content,
    )
}
