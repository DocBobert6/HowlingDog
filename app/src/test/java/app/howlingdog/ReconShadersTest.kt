package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the Neo Geo reconstruction engine
 * (v2.1.0): the GLSL declarations the renderer expects, the
 * flip constants and the orientation composition, and the grid
 * strength. This cannot compile GLSL (no GL on the JVM) — CI
 * builds the APK and the device retest proves the shaders
 * actually run — but it catches a typo'd uniform, a missing
 * varying, an unbalanced brace, or a wrong flip triple before
 * the code ever reaches CI.
 */
class ReconShadersTest {

    private fun assertBalanced(src: String) {
        var braces = 0
        var parens = 0
        for (c in src) {
            when (c) {
                '{' -> braces++
                '}' -> braces--
                '(' -> parens++
                ')' -> parens--
            }
            assertTrue("unbalanced delimiters in shader source", braces >= 0 && parens >= 0)
        }
        assertTrue("unbalanced braces in shader source", braces == 0)
        assertTrue("unbalanced parens in shader source", parens == 0)
    }

    @Test
    fun reconVertexDeclaresPositionTexcoordFlipAndVarying() {
        val src = ReconShaders.RECON_VERTEX
        assertTrue(src.contains("attribute vec2 aPos"))
        assertTrue(src.contains("attribute vec2 aTex"))
        assertTrue(src.contains("uniform float uFlipV"))
        assertTrue(src.contains("varying vec2 vTex"))
        assertTrue(src.contains("gl_Position"))
        assertBalanced(src)
    }

    @Test
    fun reconA1DeclaresEveryUniformTheRendererSets() {
        val src = ReconShaders.RECON_A1_FRAGMENT
        assertTrue(src.contains("precision mediump float"))
        assertTrue(src.contains("varying vec2 vTex"))
        assertTrue(src.contains("uniform sampler2D uTex"))
        assertTrue(src.contains("uniform vec2 uTexSize"))
        assertTrue(src.contains("uniform float uDitherTol"))
        // The five pattern detectors, and the pattern in alpha.
        assertTrue(src.contains("checker"))
        assertTrue(src.contains("stripeV"))
        assertTrue(src.contains("stripeH"))
        assertTrue(src.contains("bayer2"))
        assertTrue(src.contains("bayer4"))
        assertTrue(src.contains("gl_FragColor = vec4(c0, pattern)"))
        // No colour fold (the Geolith core has none) and no
        // Worm Light: A1 must not reference either.
        assertTrue("A1 must not colour-correct", !src.contains("colourCorrect"))
        assertTrue("A1 must not reference a gamma LUT", !src.contains("uGammaLut"))
        assertBalanced(src)
    }

    @Test
    fun reconA2DeclaresEveryUniformTheRendererSets() {
        val src = ReconShaders.RECON_A2_FRAGMENT
        assertTrue(src.contains("precision mediump float"))
        assertTrue(src.contains("varying vec2 vTex"))
        assertTrue(src.contains("uniform sampler2D uTex"))
        assertTrue(src.contains("uniform sampler2D uRawTex"))
        assertTrue(src.contains("uniform vec2 uTexSize"))
        for (name in listOf(
            "uDitherTol", "uDitherGuardHi", "uTextAlign", "uEdgeLo", "uTextStroke",
        )) {
            assertTrue("A2 must declare $name", src.contains("uniform float $name"))
        }
        // The class in alpha (0..4 over 4.0), and the collapse.
        assertTrue(src.contains("cls / 4.0"))
        assertTrue(src.contains("mean5"))
        assertTrue(src.contains("ditherAvg"))
        assertBalanced(src)
    }

    @Test
    fun reconBDeclaresEveryUniformTheRendererSets() {
        val src = ReconShaders.RECON_B_FRAGMENT
        assertTrue(src.contains("precision mediump float"))
        assertTrue(src.contains("varying vec2 vTex"))
        assertTrue(src.contains("uniform sampler2D uTex"))
        assertTrue(src.contains("uniform vec2 uTexSize"))
        for (name in listOf("uDisplayScale", "uSnap12", "uGrid")) {
            assertTrue("B must declare $name", src.contains("uniform float $name"))
        }
        // The 12-bit snap (not 15-bit) and the grid scanline.
        assertTrue(src.contains("15.0"))
        assertTrue("B must not use the 15-bit snap", !src.contains("31.0"))
        assertTrue(src.contains("gl_FragColor"))
        // No Worm Light (HowlingDog has no lamp).
        assertTrue("B must not reference Worm Light", !src.contains("uWl"))
        assertBalanced(src)
    }

    @Test
    fun flipConstantsAreTheProvedTriple() {
        assertTrue("A1 reads the Bitmap with uFlipV 1", ReconShaders.RECON_A1_FLIP_V)
        assertFalse("A2 reads texA + the raw frame with uFlipV 0", ReconShaders.RECON_A2_FLIP_V)
        assertTrue("B reads texB with uFlipV 1", ReconShaders.RECON_B_FLIP_V)
    }

    @Test
    fun orientationIsCorrectForTheShippedTriple() {
        assertTrue(
            "the shipped triple (A1 1, A2 0, B 1) must be right-side up",
            ReconShaders.reconOrientationIsCorrect(true, false, true),
        )
        assertTrue(
            "the default constants must be right-side up",
            ReconShaders.reconOrientationIsCorrect(),
        )
    }

    @Test
    fun orientationIsWrongForTheOldShippedTriple() {
        // The triple the handheld Task 6 shipped (A1 0, A2 0,
        // B 1) — the upside-down bug. The guard must reject it.
        assertFalse(
            "the old wrong triple (A1 0, A2 0, B 1) must be upside down",
            ReconShaders.reconOrientationIsCorrect(false, false, true),
        )
    }

    @Test
    fun orientationIsWrongWhenA2sInputsDisagree() {
        // A1 0 stores texA flipped, so A2's two inputs (texA
        // and the raw frame) are vertically mirrored.
        assertFalse(ReconShaders.reconOrientationIsCorrect(false, true, true))
        assertFalse(ReconShaders.reconOrientationIsCorrect(false, true, false))
        // B 0 with texB flipped shows the screen upside down.
        assertFalse(ReconShaders.reconOrientationIsCorrect(true, false, false))
    }

    @Test
    fun fboOutputFlippedFollowsTheRule() {
        // A pass into an FBO stores its output flipped exactly
        // when (inputFlipped == uFlipV).
        assertFalse(ReconShaders.fboOutputFlipped(false, true))
        assertTrue(ReconShaders.fboOutputFlipped(false, false))
        assertTrue(ReconShaders.fboOutputFlipped(true, true))
        assertFalse(ReconShaders.fboOutputFlipped(true, false))
    }

    @Test
    fun displayIsCorrectFollowsTheRule() {
        // A pass to the screen is right-side up exactly when
        // (uFlipV == inputFlipped).
        assertFalse(ReconShaders.displayIsCorrect(false, true))
        assertTrue(ReconShaders.displayIsCorrect(false, false))
        assertTrue(ReconShaders.displayIsCorrect(true, true))
        assertFalse(ReconShaders.displayIsCorrect(true, false))
    }

    @Test
    fun gridStrengthIsFifteenPercent() {
        assertEquals(0.15f, ReconShaders.RECON_GRID_STRENGTH, 0.0001f)
    }
}
