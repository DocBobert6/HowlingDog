package app.howlingdog

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the CRT shader source text: the
 * declarations the renderer expects, and balanced delimiters.
 * This cannot compile GLSL (no GL on the JVM) — CI builds the
 * APK and the device retest proves the shaders actually run —
 * but it catches a typo'd uniform, a missing varying, or an
 * unbalanced brace before the code ever reaches CI.
 */
class CrtShadersTest {

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
    fun vertexShaderDeclaresPositionTexcoordAndVarying() {
        val src = CrtShaders.VERTEX
        assertTrue(src.contains("attribute vec2 aPos"))
        assertTrue(src.contains("attribute vec2 aTex"))
        assertTrue(src.contains("varying vec2 vTex"))
        assertTrue(src.contains("gl_Position"))
        assertBalanced(src)
    }

    @Test
    fun fragmentShaderDeclaresEveryUniformTheRendererSets() {
        val src = CrtShaders.FRAGMENT
        assertTrue(src.contains("precision mediump float"))
        assertTrue(src.contains("varying vec2 vTex"))
        assertTrue(src.contains("uniform sampler2D uTex"))
        for (name in listOf(
            "uTexSize", "uScan", "uGrille", "uBlur", "uSat",
            "uWarm", "uGamma", "uBright", "uBlack", "uVignette",
        )) {
            assertTrue("fragment shader must declare $name", src.contains("uniform float $name") || src.contains("uniform vec2 $name"))
        }
        assertTrue(src.contains("texture2D(uTex, vTex)"))
        assertTrue(src.contains("gl_FragColor"))
        assertBalanced(src)
    }

    @Test
    fun plainFallbackIsJustATextureSample() {
        val src = CrtShaders.PLAIN_FRAGMENT
        assertTrue(src.contains("precision mediump float"))
        assertTrue(src.contains("uniform sampler2D uTex"))
        assertTrue(src.contains("gl_FragColor = texture2D(uTex, vTex)"))
        assertTrue("plain fallback must not reference CRT uniforms", !src.contains("uScan"))
        assertBalanced(src)
    }

    @Test
    fun edgeShaderDeclaresEveryUniformTheRendererSets() {
        val src = CrtShaders.EDGE_FRAGMENT
        assertTrue(src.contains("precision mediump float"))
        assertTrue(src.contains("varying vec2 vTex"))
        assertTrue(src.contains("uniform sampler2D uTex"))
        assertTrue(src.contains("uniform vec2 uTexSize"))
        assertTrue(src.contains("uniform float uScan"))
        assertTrue(src.contains("gl_FragColor"))
        assertBalanced(src)
    }

    @Test
    fun edgeShaderSamplesTheFull3x3Neighbourhood() {
        val src = CrtShaders.EDGE_FRAGMENT
        // The 3x3: the centre plus all eight neighbours.
        for (sample in listOf(
            "texture2D(uTex, vTex)",
            "vTex + vec2(0.0, texel.y)",
            "vTex - vec2(0.0, texel.y)",
            "vTex + vec2(texel.x, 0.0)",
            "vTex - vec2(texel.x, 0.0)",
            "vTex + vec2(texel.x, texel.y)",
            "vTex + vec2(-texel.x, texel.y)",
            "vTex + vec2(texel.x, -texel.y)",
            "vTex + vec2(-texel.x, -texel.y)",
        )) {
            assertTrue("edge shader must sample $sample", src.contains(sample))
        }
    }

    @Test
    fun edgeShaderHasDitherCornerAndColorAwareGuards() {
        val src = CrtShaders.EDGE_FRAGMENT
        // Dither: the 2x2 checkerboard test (diagonal vs both
        // orthogonal distances), gated by a dither factor.
        assertTrue(src.contains("dDiag"))
        assertTrue(src.contains("dE"))
        assertTrue(src.contains("dS"))
        assertTrue(src.contains("(1.0 - dither)"))
        // Corner: a strong gradient in both directions, gated
        // by a corner factor.
        assertTrue(src.contains("corner"))
        assertTrue(src.contains("(1.0 - corner)"))
        // Color-aware: the blend is clamped to the range of the
        // colours it mixes.
        assertTrue(src.contains("clamp(col, lo, hi)"))
        // Directional: the blend runs along the edge, with the
        // weight from the sub-pixel position.
        assertTrue(src.contains("fract(vTex * uTexSize)"))
        // Temporal stability: no noise and no time uniform, so
        // the output is a pure function of the current frame.
        assertTrue("edge shader must not use noise", !src.contains("noise"))
        assertTrue("edge shader must not use a time uniform", !src.contains("uTime"))
    }
}
