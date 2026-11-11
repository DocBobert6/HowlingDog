package app.howlingdog

/**
 * The NEOGEO System Font display spec — pure Kotlin, no Compose or
 * Android dependency, so it is unit-testable on the plain JVM.
 *
 * The font is the NEOGEO System Font by Eber (everiux365) on
 * FontStruct, a pixel-perfect monospaced recreation of the Neo Geo
 * system font (the dialog text of NAM-1975 and Mutation Nation),
 * licensed CC BY 3.0 (see NOTICE). It is the era UI's display face:
 * the select-grid headings, the boot splash, the pause-menu
 * headings, the SYSTEM SETUP headings, and the countdown. Body copy
 * and settings descriptions stay in the default font: a pixel font
 * is hard to read in long text, and its character set is limited.
 *
 * Pixel grid: the font is drawn on a 64-unit grid in a 1024-unit
 * em, so one design pixel is 1/16 em. Display sizes are multiples
 * of 16sp, so each design pixel is a whole number of sp and the
 * glyphs stay crisp instead of blurring across fractional pixels.
 * (The font is a single Regular weight that is already bold by
 * design, so display text uses FontWeight.Normal — the weight comes
 * from the font itself.)
 */
object DisplayFontSpec {
    /** One design pixel is 1/16 em, so display sizes are multiples of 16sp. */
    const val PIXEL_GRID_SP = 16

    /** Boot splash title ("HOWLINGDOG"). */
    const val BOOT_TITLE_SP = 48

    /** Boot splash prompt ("PRESS START"). */
    const val BOOT_PROMPT_SP = 16

    /** Library header ("SELECT YOUR GAME"). */
    const val LIBRARY_TITLE_SP = 16

    /** The decorative countdown ("TIME 09", pause "%d"). */
    const val COUNTDOWN_SP = 16

    /** SYSTEM SETUP heading and row titles. */
    const val SETUP_HEADING_SP = 16

    /** Pause-menu title ("CONTINUE?"). */
    const val PAUSE_TITLE_SP = 32

    /** Every display size, for the grid test. */
    val ALL_SIZES: List<Int> = listOf(
        BOOT_TITLE_SP,
        BOOT_PROMPT_SP,
        LIBRARY_TITLE_SP,
        COUNTDOWN_SP,
        SETUP_HEADING_SP,
        PAUSE_TITLE_SP,
    )

    /** True when a size keeps each design pixel a whole number of sp. */
    fun isCrispSize(sp: Int): Boolean = sp > 0 && sp % PIXEL_GRID_SP == 0

    /**
     * The characters the NEOGEO System Font covers, read from its
     * cmap: A-Z, a-z, 0-9, space, and the punctuation ! # $ % & '
     * ( ) + , - . / : < = > ?, plus em dash (U+2014), right single
     * quote (U+2019) and × (U+00D7).
     *
     * Notably absent — no display string may use these: " * ; @
     * [ ] ^ _ ` { | } ~ … – ★ ● ○ ⚙ ✓. (That is why the Neo-Geo CD
     * System Font was rejected outright: it also lacks the letter Q.)
     */
    fun covers(text: String): Boolean = text.all { it in SUPPORTED }

    private val SUPPORTED: Set<Char> = buildSet {
        addAll('A'..'Z')
        addAll('a'..'z')
        addAll('0'..'9')
        for (c in " !#$%&'()+,-./:<=>?") add(c)
        add('—') // U+2014 em dash
        add('’') // U+2019 right single quote
        add('×') // U+00D7 multiplication sign
    }

    /** Every display string the app renders in the display font. */
    val DISPLAY_STRINGS: List<String> = listOf(
        "HOWLINGDOG",
        "PRESS START",
        "SELECT YOUR GAME",
        "CONTINUE?",
        "HOWLINGDOG — SYSTEM SETUP",
        "1  SYSTEM FILES",
        "2  ADD GAMES",
        "3  PICK A GAME",
        "4  PLAY",
    )
}
