package app.howlingdog

/**
 * Per-system CRT shader presets — one look per Neo Geo system,
 * behind the CRT display settings. The Mariner pattern: the UI
 * shows the NAME ONLY, never the numbers below, and never what a
 * preset is made of. The numbers are internal starting values
 * for on-device tuning, not measured profiles.
 *
 * Each chain is made for the display its system was actually
 * played on, so the four systems do not share one look:
 * - AES (Home console): a 1990 home console on a consumer CRT
 *   TV — composite softness, warm, a visible scanline, a gentle
 *   aperture grille, and the TV's curved tube.
 * - MVS (Arcade): the cabinet on an arcade CRT monitor — RGB,
 *   sharper and brighter than a home TV, a stronger grille, and
 *   a FLATTER tube (arcade monitors bulge less than consumer
 *   TVs), with squarer corners.
 * - Neo Geo CD: a 1994 home console on a mid-90s consumer CRT
 *   TV — the home look, a touch softer and warmer than AES.
 * - Neo Geo CDZ: a 1995 home console on a late-90s consumer
 *   CRT TV — the home look, slightly flatter and brighter than
 *   the CD (late-90s tubes were flatter than early-90s ones).
 *
 * The preset also carries the system's own curvature and
 * rounding (Part 2): the home systems curve, the arcade system
 * curves less, and each rounds its corners to its own display.
 * Pure Kotlin, no Android — unit-testable on the plain JVM.
 */
/** A preset's colour grade, applied first in the shader chain, so the grade shapes the picture before scanlines or grille do. Neutral (1, 0, 1, 1, 0) reproduces the ungraded output exactly. */
data class ColorProfile(
    val saturation: Float = 1f,
    /** Warmth shift, 0..30 (Mariner's range): red up, blue down. */
    val warm: Float = 0f,
    val gamma: Float = 1f,
    val bright: Float = 1f,
    /** Black lift, 0..1: raises the floor so blacks are never crushed. */
    val black: Float = 0f,
)

data class ShaderPreset(
    val name: String,
    /** Scanline strength, 0..1. */
    val scan: Float,
    /** Aperture-grille strength, 0..1. */
    val grille: Float,
    /** Horizontal softness (composite blur), 0..1. */
    val blur: Float,
    val color: ColorProfile = ColorProfile(),
    /** Whether this system's display curves at all. */
    val curveOn: Boolean = false,
    /**
     * Base mesh pull-in for this system's display (Mariner's CRT
     * base is 0.020). Home consumer CRTs bulge; arcade monitors
     * are flatter, so MVS takes a smaller base.
     */
    val curveBase: Float = 0.020f,
    /** Percent of the base actually used, 0..100. */
    val curveAmount: Int = 30,
    /** Whether this system's display rounds its corners. */
    val roundOn: Boolean = false,
    /**
     * Corner-round percent, 0..100. The radius is
     * roundAmount/100 * 0.25 * the picture's smaller side, so 20
     * is a radius of 5% of the smaller side (Mariner's default).
     */
    val roundAmount: Int = 20,
)

object ShaderPresets {
    private val presets = mapOf(
        "aes" to ShaderPreset(
            "Hearth", 0.45f, 0.25f, 0.20f,
            color = ColorProfile(saturation = 1.05f, warm = 25f),
            curveOn = true, curveBase = 0.020f, curveAmount = 30,
            roundOn = true, roundAmount = 20,
        ),
        "mvs" to ShaderPreset(
            "Neon", 0.35f, 0.35f, 0.08f,
            color = ColorProfile(saturation = 1.08f, bright = 1.04f),
            curveOn = true, curveBase = 0.012f, curveAmount = 30,
            roundOn = true, roundAmount = 12,
        ),
        "cd" to ShaderPreset(
            "Comet", 0.42f, 0.25f, 0.15f,
            color = ColorProfile(saturation = 1.04f, warm = 15f),
            curveOn = true, curveBase = 0.020f, curveAmount = 30,
            roundOn = true, roundAmount = 20,
        ),
        "cdz" to ShaderPreset(
            "Zenith", 0.40f, 0.25f, 0.12f,
            color = ColorProfile(saturation = 1.04f, bright = 1.02f),
            curveOn = true, curveBase = 0.016f, curveAmount = 25,
            roundOn = true, roundAmount = 18,
        ),
    )

    /**
     * The preset for a system mode ("aes", "mvs", "cd", "cdz");
     * unknown modes fall back to AES. Universe BIOS on CDZ keeps
     * the CDZ preset ("uni" falls back to AES, the cart default).
     */
    fun forMode(mode: String): ShaderPreset = when (mode) {
        "cdz_unibios" -> presets.getValue("cdz")
        else -> presets[mode] ?: presets.getValue("aes")
    }

    /** The four system modes, in display order. */
    fun modes(): List<String> = presets.keys.toList()

    /** Each system's preset name, by mode — the only thing the UI ever shows. */
    fun presetNames(): Map<String, String> = presets.mapValues { it.value.name }

    /**
     * Mesh pull-in for a preset (Mariner's curvePull): the base
     * times the amount percent. 0 when curvature is disabled, so
     * the mesh is flat. Amount is clamped to 0..100.
     */
    fun curvePull(preset: ShaderPreset, enabled: Boolean, amountPct: Int): Float {
        if (!enabled) return 0f
        return preset.curveBase * amountPct.coerceIn(0, 100) / 100f
    }

    /** Corner-round strength 0..1 for a preset (roundAmount/100), 0 when disabled. */
    fun roundStrength(preset: ShaderPreset, enabled: Boolean): Float =
        if (!enabled) 0f else preset.roundAmount.coerceIn(0, 100) / 100f
}
