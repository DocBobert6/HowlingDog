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
}
