package app.howlingdog

/**
 * Per-system overlay styling — the look of the touch controls for
 * each of the four Neo Geo systems. Appearance ONLY: all four
 * systems take the exact same input (an 8-way stick plus A/B/C/D,
 * Start and Coin), so nothing here changes how a control works,
 * only what it looks like. The stick stays a stick in every mode
 * (the CD controller is really a D-pad, but the D-pad is a
 * user toggle, not a per-system default — see DpadInput).
 *
 * The look is matched to the real hardware, verified against the
 * hardware itself:
 * - AES (Home console): the home controller's buttons sit in a
 *   gentle arc (a curved row of four, middle higher), coloured
 *   the Neo Geo rainbow — A red, B yellow, C green, D blue.
 *   The stick's nub is red-tinted, the ball-top of the AES stick.
 * - MVS (Arcade): the cabinet panel puts A in the thumb position
 *   (bottom-left, under B) with B, C and D in a row above it
 *   ("BCD / A" — the standard MVS panel). Same rainbow colours.
 *   The stick's nub is red-tinted, the cabinet ball-top.
 * - Neo Geo CD / CDZ: the CD controller is a SNES-like joypad,
 *   so its four face buttons sit in a diamond (C top, B left,
 *   D right, A bottom) in the same rainbow colours. The stick
 *   discs go grey/silver, the pad's shell colour — the shape
 *   is still the PS1-style stick (filled base disc, smaller
 *   filled nub riding inside), only the colours change.
 *
 * Button positions are offsets in dp at scale 1.0, relative to
 * the cluster centre (the centre of the buttons' bounding box).
 * The effective size is the Settings preset times the cluster
 * scale, so the offsets scale with the cluster. Pure Kotlin,
 * no Android — unit-testable on the plain JVM.
 */
object OverlayTheme {

    /** The four-button arrangement for a system. */
    enum class ButtonLayout {
        /** The 2x2 grid (the current layout; the fallback). */
        GRID,
        /** AES: a curved row of four, middle higher. */
        ARC,
        /** MVS: A bottom-left (thumb), B C D in a row above. */
        BCD_A,
        /** Neo Geo CD: the pad's diamond (C top, B left, D right, A bottom). */
        DIAMOND,
    }

    /** One button's colours: its background and its label. */
    data class ButtonStyle(val background: Int, val label: Int)

    /** The whole overlay look for one system. Colours are ARGB ints. */
    data class Theme(
        val layout: ButtonLayout,
        /** Face-button styles by id ("A", "B", "C", "D"). */
        val buttonStyles: Map<String, ButtonStyle>,
        /** Stick base disc: top and bottom of the vertical gradient. */
        val stickBaseTop: Int,
        val stickBaseBottom: Int,
        /** Stick nub disc: top and bottom of the vertical gradient. */
        val stickNubTop: Int,
        val stickNubBottom: Int,
        /** The hairline/outline colour (gold on every system). */
        val accent: Int,
    )

    // The Neo Geo rainbow, muted to sit in the app's maroon-and-gold
    // palette (no neon): A red, B yellow, C green, D blue. White
    // labels on the dark colours, dark labels on the yellow.
    private val STYLE_A = ButtonStyle(background = 0xFFC0392B.toInt(), label = 0xFFFFFFFF.toInt())
    private val STYLE_B = ButtonStyle(background = 0xFFE8C33F.toInt(), label = 0xFF311218.toInt())
    private val STYLE_C = ButtonStyle(background = 0xFF2E7D32.toInt(), label = 0xFFFFFFFF.toInt())
    private val STYLE_D = ButtonStyle(background = 0xFF2E6FB0.toInt(), label = 0xFFFFFFFF.toInt())
    private val RAINBOW: Map<String, ButtonStyle> = mapOf(
        "A" to STYLE_A, "B" to STYLE_B, "C" to STYLE_C, "D" to STYLE_D,
    )

    /** The PS1 stick's maroon discs (AES/MVS — the ball-top sticks). */
    private val STICK_BASE_TOP_MAROON: Int = 0xFF552732.toInt()
    private val STICK_BASE_BOTTOM_MAROON: Int = 0xFF311218.toInt()
    /** The red ball-top: the nub's top is red, its bottom maroon. */
    private val STICK_NUB_TOP_RED: Int = 0xFFA83A2A.toInt()
    private val STICK_NUB_BOTTOM_MAROON: Int = 0xFF552732.toInt()
    /** The CD pad's grey shell (CD/CDZ — the stick stands in for the pad). */
    private val STICK_BASE_TOP_GREY: Int = 0xFF6E7378.toInt()
    private val STICK_BASE_BOTTOM_GREY: Int = 0xFF3A3F45.toInt()
    private val STICK_NUB_TOP_GREY: Int = 0xFF8A9096.toInt()
    private val STICK_NUB_BOTTOM_GREY: Int = 0xFF4A4F55.toInt()

    private val GOLD: Int = EraUi.Palette.GOLD

    private val AES_THEME = Theme(
        layout = ButtonLayout.ARC,
        buttonStyles = RAINBOW,
        stickBaseTop = STICK_BASE_TOP_MAROON,
        stickBaseBottom = STICK_BASE_BOTTOM_MAROON,
        stickNubTop = STICK_NUB_TOP_RED,
        stickNubBottom = STICK_NUB_BOTTOM_MAROON,
        accent = GOLD,
    )
    private val MVS_THEME = Theme(
        layout = ButtonLayout.BCD_A,
        buttonStyles = RAINBOW,
        stickBaseTop = STICK_BASE_TOP_MAROON,
        stickBaseBottom = STICK_BASE_BOTTOM_MAROON,
        stickNubTop = STICK_NUB_TOP_RED,
        stickNubBottom = STICK_NUB_BOTTOM_MAROON,
        accent = GOLD,
    )
    private val CD_THEME = Theme(
        layout = ButtonLayout.DIAMOND,
        buttonStyles = RAINBOW,
        stickBaseTop = STICK_BASE_TOP_GREY,
        stickBaseBottom = STICK_BASE_BOTTOM_GREY,
        stickNubTop = STICK_NUB_TOP_GREY,
        stickNubBottom = STICK_NUB_BOTTOM_GREY,
        accent = GOLD,
    )

    /** The overlay look for a system mode ("aes", "mvs", "cd", "cdz"). Unknown modes fall back to AES. */
    fun themeFor(mode: String): Theme = when (mode.lowercase()) {
        "mvs" -> MVS_THEME
        "cd", "cdz" -> CD_THEME
        else -> AES_THEME
    }

    /**
     * The default button offsets for a layout, in dp at scale 1.0,
     * relative to the cluster centre (the centre of the buttons'
     * bounding box). Keys: "A", "B", "C", "D", "Coin", "Start".
     * A/B/C/D are 56dp buttons, Coin/Start are 48dp.
     */
    fun buttonOffsets(layout: ButtonLayout): Map<String, Pair<Float, Float>> = when (layout) {
        // The current 2x2 grid: 120dp wide, 180dp tall at scale 1
        // (2x56 + 8 wide; 2x56 + 8 + 12 + 48 tall; centre 60/90).
        // Rows: A/B centres 28, C/D centres 92, Coin/Start 156.
        ButtonLayout.GRID -> mapOf(
            "A" to (-32f to -62f),
            "B" to (32f to -62f),
            "C" to (-32f to 2f),
            "D" to (32f to 2f),
            "Coin" to (-32f to 66f),
            "Start" to (32f to 66f),
        )
        // AES arc: four buttons in a curved row, middle higher
        // (pitch 64dp), Coin/Start beneath. 248dp wide, 128dp tall.
        ButtonLayout.ARC -> mapOf(
            "A" to (-96f to -24f),
            "B" to (-32f to -36f),
            "C" to (32f to -36f),
            "D" to (96f to -24f),
            "Coin" to (-32f to 40f),
            "Start" to (32f to 40f),
        )
        // MVS panel: A in the thumb position (bottom-left, under B),
        // B C D in a row above. 184dp wide, 180dp tall.
        ButtonLayout.BCD_A -> mapOf(
            "A" to (-64f to 2f),
            "B" to (-64f to -62f),
            "C" to (0f to -62f),
            "D" to (64f to -62f),
            "Coin" to (-32f to 66f),
            "Start" to (32f to 66f),
        )
        // CD pad diamond: C top, B left, D right, A bottom.
        // Adjacent buttons are ~68dp apart (centre to centre), so
        // the 56dp buttons never overlap. 152dp wide, 212dp tall.
        ButtonLayout.DIAMOND -> mapOf(
            "A" to (0f to 18f),
            "B" to (-48f to -30f),
            "C" to (0f to -78f),
            "D" to (48f to -30f),
            "Coin" to (-32f to 82f),
            "Start" to (32f to 82f),
        )
    }

    /** A button's diameter in dp at scale 1.0 (Coin/Start are smaller). */
    fun buttonSize(id: String): Float = if (id == "Coin" || id == "Start") 48f else 56f

    /**
     * A layout's bounding box (width, height) in dp at scale 1.0,
     * from the button offsets and sizes — the single source of
     * truth for the cluster geometry (offset, drag, resize grip).
     */
    fun clusterSize(layout: ButtonLayout): Pair<Float, Float> {
        val offsets = buttonOffsets(layout)
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for ((id, off) in offsets) {
            val half = buttonSize(id) / 2f
            minX = minOf(minX, off.first - half)
            maxX = maxOf(maxX, off.first + half)
            minY = minOf(minY, off.second - half)
            maxY = maxOf(maxY, off.second + half)
        }
        return (maxX - minX) to (maxY - minY)
    }
}
