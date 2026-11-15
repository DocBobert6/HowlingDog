package app.howlingdog

/**
 * The reconstruction's per-system parameters (v2.1.0). One
 * engine, four parameter sets (MVS, AES, CD, CDZ) — they start
 * identical (same 304x224 frame, same 4096-colour palette, same
 * sprite system) and are independently tunable. The starting
 * values are the handheld GBA engine's (the closest analogue:
 * most colours, thinnest outlines), retuned for Neo Geo's
 * 12-bit palette. Pure Kotlin, unit-testable on the plain JVM.
 */
data class ReconParams(
    /** The dither tolerance (the pattern detectors' distance threshold). */
    val ditherTol: Float = 0.10f,
    /** The edge threshold (the 3x3 Sobel strength that makes a texel an edge). */
    val edgeLo: Float = 0.05f,
    /** The shade-distance guard (the dither collapse only runs when the shades are within this). */
    val ditherGuardHi: Float = 0.7f,
    /** The text axis-alignment threshold (the 5x5-ring gradient ratio that makes a texel text). */
    val textAlign: Float = 0.05f,
    /** The stroke-width run test (Neo Geo: off — the basic text test suffices for the small HUD font; large title text is a sprite and is smoothed). */
    val textStroke: Boolean = false,
    /** The 12-bit snap (the blended colour snaps to the nearest 12-bit value). */
    val snap12: Boolean = true,
) {
    companion object {
        /**
         * The parameter set for a system mode ("aes", "mvs",
         * "cd", "cdz"). The four systems start identical; a
         * future per-system tune lands here, one mode at a
         * time. Unknown modes fall back to the MVS set.
         */
        fun forMode(mode: String): ReconParams = when (mode) {
            "mvs" -> ReconParams()
            "aes" -> ReconParams()
            "cd" -> ReconParams()
            "cdz" -> ReconParams()
            else -> ReconParams()
        }
    }
}
