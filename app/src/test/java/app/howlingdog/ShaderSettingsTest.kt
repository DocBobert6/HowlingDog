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

    @Test
    fun edgeModeDefaultsOff() {
        val s = ShaderSettings(MapStore())
        assertEquals(EdgeMode.OFF, s.edgeMode("aes"))
        assertEquals(EdgeMode.OFF, s.edgeMaster())
        assertEquals(DisplayMode.CRT, s.displayMode("aes"))
    }

    @Test
    fun edgeModeRoundTrips() {
        val store = MapStore()
        val s = ShaderSettings(store)
        s.setEdgeMaster(EdgeMode.EDGE)
        assertEquals(EdgeMode.EDGE, s.edgeMaster())
        assertEquals(EdgeMode.EDGE, s.edgeMode("aes"))
        s.setEdgeMaster(EdgeMode.SHARP_CRT)
        assertEquals(EdgeMode.SHARP_CRT, s.edgeMaster())
        assertEquals(EdgeMode.SHARP_CRT, s.edgeMode("mvs"))
        s.setEdgeMaster(EdgeMode.OFF)
        assertEquals(EdgeMode.OFF, s.edgeMaster())
    }

    @Test
    fun perSystemEdgeModeWinsOverMaster() {
        val store = MapStore()
        val s = ShaderSettings(store)
        s.setEdgeMaster(EdgeMode.EDGE)
        s.setEdgeMode("aes", EdgeMode.SHARP_CRT)
        assertEquals(EdgeMode.SHARP_CRT, s.edgeMode("aes"))
        assertEquals(EdgeMode.EDGE, s.edgeMode("mvs"))
        s.setEdgeMode("aes", EdgeMode.OFF)
        assertEquals(EdgeMode.OFF, s.edgeMode("aes"))
    }

    @Test
    fun badEdgeValuesFallBackToOff() {
        val store = MapStore()
        val s = ShaderSettings(store)
        store.put(ShaderSettings.KEY_EDGE_MASTER, "sharper")
        assertEquals(EdgeMode.OFF, s.edgeMaster())
        store.put(ShaderSettings.KEY_EDGE_MASTER, "SHARP_CRT")
        assertEquals("an underscore is not the stored form", EdgeMode.OFF, s.edgeMaster())
        store.put(ShaderSettings.KEY_EDGE_MASTER, "sharpcrt")
        assertEquals(EdgeMode.SHARP_CRT, s.edgeMaster())
        store.put(ShaderSettings.KEY_EDGE_PREFIX + "aes", "EDGE")
        assertEquals("the stored form is case-insensitive on read", EdgeMode.EDGE, s.edgeMode("aes"))
    }

    @Test
    fun edgeModeWinsOverCrt() {
        // The exclusivity rule: the edge shader is the
        // alternative display mode, so when it is on the CRT is
        // off, whatever the CRT toggle says.
        assertEquals(DisplayMode.EDGE, resolveDisplayMode(true, EdgeMode.EDGE))
        assertEquals(DisplayMode.EDGE, resolveDisplayMode(false, EdgeMode.EDGE))
        assertEquals(DisplayMode.SHARP_CRT, resolveDisplayMode(true, EdgeMode.SHARP_CRT))
        assertEquals(DisplayMode.SHARP_CRT, resolveDisplayMode(false, EdgeMode.SHARP_CRT))
        // With the edge mode off, the CRT toggle decides.
        assertEquals(DisplayMode.CRT, resolveDisplayMode(true, EdgeMode.OFF))
        assertEquals(DisplayMode.PLAIN, resolveDisplayMode(false, EdgeMode.OFF))
    }

    @Test
    fun displayModeFollowsTheStoredSettings() {
        val store = MapStore()
        val s = ShaderSettings(store)
        assertEquals(DisplayMode.CRT, s.displayMode("aes"))
        s.setEdgeMaster(EdgeMode.EDGE)
        assertEquals(DisplayMode.EDGE, s.displayMode("aes"))
        s.setShaderMaster(false)
        assertEquals("the CRT toggle is moot while the edge mode is on", DisplayMode.EDGE, s.displayMode("aes"))
        s.setEdgeMaster(EdgeMode.OFF)
        assertEquals(DisplayMode.PLAIN, s.displayMode("aes"))
        s.setShaderMaster(true)
        assertEquals(DisplayMode.CRT, s.displayMode("aes"))
    }
}
