package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the per-system CRT shader presets:
 * lookup, one-word names, and the per-system curve/round
 * values. No Android framework calls.
 */
class ShaderPresetsTest {

    @Test
    fun forModeReturnsEachSystemsPreset() {
        assertEquals("Hearth", ShaderPresets.forMode("aes").name)
        assertEquals("Neon", ShaderPresets.forMode("mvs").name)
        assertEquals("Comet", ShaderPresets.forMode("cd").name)
        assertEquals("Zenith", ShaderPresets.forMode("cdz").name)
        // Phase 9, item 9 — Universe BIOS on CDZ keeps the CDZ preset.
        assertEquals("Zenith", ShaderPresets.forMode("cdz_unibios").name)
    }

    @Test
    fun unknownModeFallsBackToAes() {
        assertEquals("Hearth", ShaderPresets.forMode("bogus").name)
        assertEquals("Hearth", ShaderPresets.forMode("").name)
    }

    @Test
    fun namesAreOneWordDistinctAndNotMariners() {
        val marinerNames = setOf(
            "Jade", "Prism", "Dusk", "Ember", "Violet", "Blast",
            "Cobalt", "Fog", "Lantern", "Twin", "Orbit", "Vertex",
        )
        val names = ShaderPresets.modes().map { ShaderPresets.forMode(it).name }
        assertEquals(4, names.toSet().size)
        for (name in names) {
            assertTrue("name must be one word: $name", !name.contains(" "))
            assertTrue("name must not be blank", name.isNotBlank())
            assertNotEquals("name must not reuse a Mariner preset: $name", true, name in marinerNames)
        }
    }

    @Test
    fun strengthsAreInRangeAndGradesAreSane() {
        for (mode in ShaderPresets.modes()) {
            val p = ShaderPresets.forMode(mode)
            assertTrue(p.scan in 0f..1f)
            assertTrue(p.grille in 0f..1f)
            assertTrue(p.blur in 0f..1f)
            assertTrue(p.color.saturation > 0f)
            assertTrue(p.color.warm in 0f..30f)
            assertTrue(p.color.gamma > 0f)
            assertTrue(p.color.bright > 0f)
            assertTrue(p.color.black in 0f..1f)
            assertTrue(p.curveBase > 0f)
            assertTrue(p.curveAmount in 0..100)
            assertTrue(p.roundAmount in 0..100)
        }
    }

    @Test
    fun arcadeMonitorCurvesLessThanHomeTv() {
        val aes = ShaderPresets.forMode("aes")
        val mvs = ShaderPresets.forMode("mvs")
        val cdz = ShaderPresets.forMode("cdz")
        // Same amount percent, but the arcade monitor's base is
        // smaller (flatter tube) and the late-90s TV sits between.
        val aesPull = ShaderPresets.curvePull(aes, true, 30)
        val mvsPull = ShaderPresets.curvePull(mvs, true, 30)
        val cdzPull = ShaderPresets.curvePull(cdz, true, 25)
        assertTrue("MVS must curve less than AES", mvsPull < aesPull)
        assertTrue("CDZ must curve less than AES", cdzPull < aesPull)
        assertTrue("MVS must curve less than CDZ", mvsPull < cdzPull)
        assertEquals("AES pull is the Mariner CRT base at 30%", 0.020f * 0.30f, aesPull, 0.00001f)
    }

    @Test
    fun curvePullDisabledIsZeroAndAmountClamps() {
        val aes = ShaderPresets.forMode("aes")
        assertEquals(0f, ShaderPresets.curvePull(aes, false, 30), 0f)
        assertEquals(0.020f, ShaderPresets.curvePull(aes, true, 150), 0.0001f)
        assertEquals(0f, ShaderPresets.curvePull(aes, true, -10), 0f)
    }

    @Test
    fun roundStrengthFollowsPresetAndToggle() {
        assertEquals(0.20f, ShaderPresets.roundStrength(ShaderPresets.forMode("aes"), true), 0.001f)
        assertEquals(0.12f, ShaderPresets.roundStrength(ShaderPresets.forMode("mvs"), true), 0.001f)
        assertEquals(0f, ShaderPresets.roundStrength(ShaderPresets.forMode("aes"), false), 0f)
    }

    @Test
    fun mvsIsSharperAndBrighterThanAes() {
        val aes = ShaderPresets.forMode("aes")
        val mvs = ShaderPresets.forMode("mvs")
        assertTrue("MVS (RGB arcade monitor) must blur less than AES (composite TV)", mvs.blur < aes.blur)
        assertTrue("MVS must grille more than AES", mvs.grille > aes.grille)
    }
}
