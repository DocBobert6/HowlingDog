package app.howlingdog

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * The HowlingDog theme: the icon's palette (deep maroon, gold,
 * dark charcoal) as the Material3 colour scheme. The sampled
 * values live in EraUi.Palette as ARGB ints so they are testable
 * on the plain JVM; this file only turns them into Compose
 * colours. The old default Material3 scheme (purple primary,
 * light surfaces) is gone everywhere — Settings, dialogs and
 * dropdowns all follow this scheme now.
 */
/**
 * The display font: the NEOGEO System Font (Eber / everiux365,
 * FontStruct; CC BY 3.0, see NOTICE) — a pixel-perfect monospaced
 * recreation of the Neo Geo system font. It is the era UI's display
 * face (select-grid headings, boot splash, pause-menu headings,
 * SYSTEM SETUP headings, countdown); body copy stays in the default
 * font. The font is a single Regular weight that is already bold by
 * design, so display text uses FontWeight.Normal — the weight comes
 * from the font itself. Sizes come from DisplayFontSpec (multiples
 * of the 16sp pixel grid) so they stay crisp.
 */
val DisplayFontFamily = FontFamily(Font(R.font.neogeo_system_font, FontWeight.Normal))

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
