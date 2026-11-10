package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the CRT display settings: defaults,
 * round-trips, and the per-system → master → preset-default
 * resolution chain. No Android framework calls — a fake
 * in-memory store.
 */
class ShaderSettingsTest {

    private class MapStore : OptionStore {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    }

    @Test
    fun shaderDefaultsOnForEverySystem() {
        val s = ShaderSettings(MapStore())
        for (mode in ShaderPresets.modes()) {
            assertTrue("shader must default on for $mode", s.shaderOn(mode))
        }
    }

    @Test
    fun curvatureDefaultsToEachPresetsOwnValue() {
        val s = ShaderSettings(MapStore())
        for (mode in ShaderPresets.modes()) {
            assertEquals(
                ShaderPresets.forMode(mode).curveOn,
                s.curvatureOn(mode),
            )
        }
    }

    @Test
    fun masterOffTurnsShaderOffForEverySystem() {
        val s = ShaderSettings(MapStore())
        s.setShaderMaster(false)
        for (mode in ShaderPresets.modes()) {
            assertFalse("master off must turn the shader off for $mode", s.shaderOn(mode))
        }
        s.setShaderMaster(true)
        for (mode in ShaderPresets.modes()) {
            assertTrue(s.shaderOn(mode))
        }
    }

    @Test
    fun masterOffTurnsCurvatureOffForEverySystem() {
        val s = ShaderSettings(MapStore())
        s.setCurvatureMaster(false)
        for (mode in ShaderPresets.modes()) {
            assertFalse("master off must turn curvature off for $mode", s.curvatureOn(mode))
        }
    }

    @Test
    fun perSystemOverrideWinsOverMaster() {
        val s = ShaderSettings(MapStore())
        s.setShaderMaster(false)
        s.setShaderOn("mvs", true)
        assertTrue("MVS override on must win over master off", s.shaderOn("mvs"))
        assertFalse("AES must still follow master off", s.shaderOn("aes"))
        s.setShaderOn("mvs", false)
        assertFalse(s.shaderOn("mvs"))
    }

    @Test
    fun perSystemCurvatureOverrideWinsOverMaster() {
        val s = ShaderSettings(MapStore())
        s.setCurvatureMaster(false)
        s.setCurvatureOn("aes", true)
        assertTrue(s.curvatureOn("aes"))
        assertFalse(s.curvatureOn("mvs"))
    }

    @Test
    fun masterGettersReadOnlyTheMasterKey() {
        val s = ShaderSettings(MapStore())
        assertTrue(s.shaderMasterOn())
        assertTrue(s.curvatureMasterOn())
        s.setShaderOn("aes", false)
        assertTrue("a per-system override must not move the master", s.shaderMasterOn())
        s.setShaderMaster(false)
        assertFalse(s.shaderMasterOn())
    }

    @Test
    fun unknownModeFallsBackToAesPreset() {
        val s = ShaderSettings(MapStore())
        assertTrue(s.shaderOn("bogus"))
        assertEquals(ShaderPresets.forMode("aes").curveOn, s.curvatureOn("bogus"))
    }
}
