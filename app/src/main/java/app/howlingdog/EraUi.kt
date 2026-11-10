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
    /** Era palette: bold arcade colours for the portrait tiles. */
    val TILE_COLORS: List<Int> = listOf(
        0xFFD21F2B.toInt(), // red
        0xFF1F5FD2.toInt(), // blue
        0xFFF2C200.toInt(), // yellow
        0xFF1E9E4A.toInt(), // green
        0xFFF27A00.toInt(), // orange
        0xFF7A3FD1.toInt(), // purple
        0xFF00998A.toInt(), // teal
        0xFFD1007A.toInt(), // magenta
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
