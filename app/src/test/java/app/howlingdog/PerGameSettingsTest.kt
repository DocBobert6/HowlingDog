package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the per-game configuration profiles
 * (Phase 9, item 8): an override wins over the global value, an
 * unset value falls back to it, and clearing a profile restores
 * global behaviour. No Android framework calls — a fake in-memory
 * store.
 */
class PerGameSettingsTest {

    private class MapStore : OptionStore {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    }

    private val game = "kof98.neo"
    private val other = "mslug3.neo"

    @Test
    fun unsetCoreOptionFallsBackToGlobal() {
        val p = PerGameSettings(MapStore())
        assertNull(p.coreOverride(game, "geolith_region"))
        assertEquals("us", p.resolveCore(game, "geolith_region", "us"))
        assertFalse(p.hasProfile(game))
    }

    @Test
    fun setCoreOptionWinsOverGlobal() {
        val p = PerGameSettings(MapStore())
        p.setCoreOverride(game, "geolith_region", "jp")
        assertEquals("jp", p.coreOverride(game, "geolith_region"))
        assertEquals("jp", p.resolveCore(game, "geolith_region", "us"))
        assertTrue(p.hasProfile(game))
    }

    @Test
    fun clearingOneCoreOptionRestoresGlobalForItOnly() {
        val p = PerGameSettings(MapStore())
        p.setCoreOverride(game, "geolith_region", "jp")
        p.setCoreOverride(game, "geolith_palette", "raw")
        p.setCoreOverride(game, "geolith_region", null)
        assertNull(p.coreOverride(game, "geolith_region"))
        assertEquals("us", p.resolveCore(game, "geolith_region", "us"))
        assertEquals("raw", p.coreOverride(game, "geolith_palette"))
        assertTrue("the palette override still stands", p.hasProfile(game))
    }

    @Test
    fun unknownCoreKeyIsNeverStored() {
        val p = PerGameSettings(MapStore())
        p.setCoreOverride(game, "geolith_unibios_hw", "aes")
        p.setCoreOverride(game, "geolith_overscan_t", "0")
        p.setCoreOverride(game, "geolith_memcard", "off")
        p.setCoreOverride(game, "geolith_cd_speed_hack", "enabled")
        p.setCoreOverride(game, "geolith_bogus", "x")
        assertNull(p.coreOverride(game, "geolith_unibios_hw"))
        assertNull(p.coreOverride(game, "geolith_overscan_t"))
        assertNull(p.coreOverride(game, "geolith_memcard"))
        assertNull(p.coreOverride(game, "geolith_cd_speed_hack"))
        assertFalse("no override may survive for a non-per-game key", p.hasProfile(game))
    }

    @Test
    fun invalidCoreValueIsNeverStored() {
        val p = PerGameSettings(MapStore())
        p.setCoreOverride(game, "geolith_region", "xx")
        p.setCoreOverride(game, "geolith_oc", "maybe")
        assertNull(p.coreOverride(game, "geolith_region"))
        assertNull(p.coreOverride(game, "geolith_oc"))
        assertFalse(p.hasProfile(game))
    }

    @Test
    fun everyPerGameCoreKeyRoundTrips() {
        val p = PerGameSettings(MapStore())
        for (key in PerGameSettings.CORE_KEYS) {
            val opt = CoreOptions.byKey(key)!!
            val value = opt.values.last().first
            p.setCoreOverride(game, key, value)
            assertEquals(value, p.coreOverride(game, key))
            assertEquals(value, p.resolveCore(game, key, opt.defaultValue))
        }
    }

    @Test
    fun overridesArePerGame() {
        val p = PerGameSettings(MapStore())
        p.setCoreOverride(game, "geolith_region", "jp")
        p.setShaderOverride(game, false)
        assertNull(p.coreOverride(other, "geolith_region"))
        assertEquals("us", p.resolveCore(other, "geolith_region", "us"))
        assertNull(p.shaderOverride(other))
        assertFalse(p.hasProfile(other))
    }

    @Test
    fun shaderOverrideWinsOverGlobalAndClears() {
        val p = PerGameSettings(MapStore())
        assertNull(p.shaderOverride(game))
        assertTrue(p.resolveShader(game, true))
        p.setShaderOverride(game, false)
        assertEquals(false, p.shaderOverride(game))
        assertFalse("the override off must win over global on", p.resolveShader(game, true))
        assertFalse(p.resolveShader(game, false))
        p.setShaderOverride(game, null)
        assertNull(p.shaderOverride(game))
        assertTrue(p.resolveShader(game, true))
        assertFalse(p.hasProfile(game))
    }

    @Test
    fun curvatureOverrideWinsOverGlobalAndClears() {
        val p = PerGameSettings(MapStore())
        assertNull(p.curvatureOverride(game))
        assertFalse(p.resolveCurvature(game, false))
        p.setCurvatureOverride(game, true)
        assertEquals(true, p.curvatureOverride(game))
        assertTrue(p.resolveCurvature(game, false))
        p.setCurvatureOverride(game, null)
        assertNull(p.curvatureOverride(game))
        assertFalse(p.resolveCurvature(game, false))
    }

    @Test
    fun leftControlOverrideWinsOverGlobalAndClears() {
        val p = PerGameSettings(MapStore())
        assertNull(p.leftControlOverride(game))
        assertEquals(LeftControl.STICK, p.resolveLeftControl(game, LeftControl.STICK))
        p.setLeftControlOverride(game, LeftControl.DPAD)
        assertEquals(LeftControl.DPAD, p.leftControlOverride(game))
        assertEquals(LeftControl.DPAD, p.resolveLeftControl(game, LeftControl.STICK))
        p.setLeftControlOverride(game, null)
        assertNull(p.leftControlOverride(game))
        assertEquals(LeftControl.STICK, p.resolveLeftControl(game, LeftControl.STICK))
        assertFalse(p.hasProfile(game))
    }

    @Test
    fun badStoredValuesReadAsUnset() {
        val store = MapStore()
        store.map["pergame.$game.display.shader"] = "maybe"
        store.map["pergame.$game.display.curvature"] = "yes"
        store.map["pergame.$game.controls.leftControl"] = "wheel"
        store.map["pergame.$game.geolith_region"] = "xx"
        val p = PerGameSettings(store)
        assertNull(p.shaderOverride(game))
        assertNull(p.curvatureOverride(game))
        assertNull(p.leftControlOverride(game))
        assertNull(p.coreOverride(game, "geolith_region"))
        assertTrue(p.resolveShader(game, true))
        assertFalse(p.resolveCurvature(game, false))
        assertEquals(LeftControl.DPAD, p.resolveLeftControl(game, LeftControl.DPAD))
        assertFalse("bad values must not count as a profile", p.hasProfile(game))
    }

    @Test
    fun clearDropsEveryOverrideAndRestoresGlobal() {
        val p = PerGameSettings(MapStore())
        p.setCoreOverride(game, "geolith_region", "jp")
        p.setCoreOverride(game, "geolith_freeplay", "on")
        p.setShaderOverride(game, false)
        p.setCurvatureOverride(game, false)
        p.setLeftControlOverride(game, LeftControl.DPAD)
        assertTrue(p.hasProfile(game))
        p.clear(game)
        assertNull(p.coreOverride(game, "geolith_region"))
        assertNull(p.coreOverride(game, "geolith_freeplay"))
        assertNull(p.shaderOverride(game))
        assertNull(p.curvatureOverride(game))
        assertNull(p.leftControlOverride(game))
        assertFalse(p.hasProfile(game))
        assertEquals("us", p.resolveCore(game, "geolith_region", "us"))
        assertTrue(p.resolveShader(game, true))
        assertTrue(p.resolveCurvature(game, true))
        assertEquals(LeftControl.STICK, p.resolveLeftControl(game, LeftControl.STICK))
    }

    @Test
    fun clearOnlyAffectsThatGame() {
        val p = PerGameSettings(MapStore())
        p.setCoreOverride(game, "geolith_region", "jp")
        p.setCoreOverride(other, "geolith_region", "eu")
        p.clear(game)
        assertNull(p.coreOverride(game, "geolith_region"))
        assertEquals("eu", p.coreOverride(other, "geolith_region"))
        assertTrue(p.hasProfile(other))
    }

    @Test
    fun storedKeysStayNamespacedPerGame() {
        val store = MapStore()
        val p = PerGameSettings(store)
        p.setCoreOverride(game, "geolith_region", "jp")
        assertEquals("jp", store.map["pergame.$game.geolith_region"])
        assertFalse("no global key may be written", store.map.containsKey("geolith_region"))
    }
}
