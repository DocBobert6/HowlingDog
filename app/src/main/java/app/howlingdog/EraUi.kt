package app.howlingdog

/**
 * Phase 5 — era UI skin (fighting-game select grid). The pure logic
 * of the skin lives here, with no Compose dependency, so it is
 * unit-testable on the plain JVM:
 * - tile colour: a stable bold arcade colour per game name, so a
 *   tile looks complete with no art at all.
 * - countdown: the decorative select-screen timer. It counts down
 *   and wraps, and it NEVER triggers an action — the timer is
 *   flavour, not a deadline (an auto-starting library would be
 *   hostile).
 * - monogram: the big letter a tile shows for a game with no art.
 */
object EraUi {
    /**
     * The HowlingDog palette, sampled from Robert's icon (the
     * howling dog on a maroon circuit board with gold traces):
     * deep maroon/burgundy, gold, dark charcoal. The bright gold is
     * the highlight gold from the icon's arcs and chip (#EDD192
     * samples); the muted gold is the average of the circuit
     * traces. No bright red, no navy — the old palette is gone.
     * Stored as ARGB ints so the palette is testable on the plain
     * JVM; Theme.kt turns them into the Material3 colour scheme.
     */
    object Palette {
        val BACKGROUND: Int = 0xFF12171A.toInt() // dark charcoal
        val SURFACE: Int = 0xFF241014.toInt() // near-black maroon
        val SURFACE_RAISED: Int = 0xFF311218.toInt() // deep maroon
        val MAROON: Int = 0xFF552732.toInt() // maroon
        val MAROON_LIGHT: Int = 0xFF6E3B45.toInt() // rose maroon
        val GOLD: Int = 0xFFE8C88F.toInt() // bright gold (highlights)
        val GOLD_MUTED: Int = 0xFFAB8D5A.toInt() // muted gold (traces)
        val TEXT: Int = 0xFFF3E7CE.toInt() // warm off-white
        val TEXT_MUTED: Int = 0xFFC9B18A.toInt() // muted gold-tan
        val WARNING: Int = 0xFFC25B4A.toInt() // muted terracotta (no bright red)
        val ON_GOLD: Int = 0xFF311218.toInt() // dark text on gold
    }

    /** Era palette: maroon, bronze and charcoal shades from the icon, so the tiles stay distinguishable per game. */
    val TILE_COLORS: List<Int> = listOf(
        0xFF311218.toInt(), // deep maroon
        0xFF552732.toInt(), // maroon
        0xFF6E3B45.toInt(), // rose maroon
        0xFF241014.toInt(), // near-black maroon
        0xFF775F47.toInt(), // warm brown (dog's fur)
        0xFF12171A.toInt(), // dark charcoal
        0xFF6B4E33.toInt(), // dark brass
        0xFF3D1F26.toInt(), // mid-dark maroon
    )

    /** A stable tile colour (ARGB) for a game name: same name, same colour. */
    fun tileColorArgb(name: String): Int {
        val index = (name.hashCode() and 0x7fffffff) % TILE_COLORS.size
        return TILE_COLORS[index]
    }

    /** The monogram a tile shows: the first letter or digit of the name, upper-cased. */
    fun monogram(name: String): String {
        val first = name.trim().firstOrNull { it.isLetterOrDigit() } ?: return "?"
        return first.uppercaseChar().toString()
    }

    /**
     * The decorative countdown's next value: counts down to 0, then
     * wraps back to [max]. The value is display only — nothing in
     * the app ever acts on it.
     */
    fun countdownNext(value: Int, max: Int): Int {
        require(max > 0) { "max must be positive" }
        return if (value <= 0) max else value - 1
    }
}
