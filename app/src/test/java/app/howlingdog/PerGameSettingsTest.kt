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

    // 2.0, feature 2 — per-game saved control positions.

    @Test
    fun unsetLayoutFallsBackToGlobal() {
        val p = PerGameSettings(MapStore())
        val stick = ControlPosition(0.14f, 0.72f)
        val buttons = ControlPosition(0.86f, 0.72f)
        assertNull(p.stickPositionOverride(game))
        assertNull(p.buttonsPositionOverride(game))
        assertNull(p.stickScaleOverride(game))
        assertNull(p.buttonsScaleOverride(game))
        assertNull(p.dpadScaleOverride(game))
        assertTrue(p.buttonSizeMultsOverride(game).isEmpty())
        assertTrue(p.buttonOffsetsOverride(game).isEmpty())
        assertEquals(stick, p.resolveStickPosition(game, stick))
        assertEquals(buttons, p.resolveButtonsPosition(game, buttons))
        assertEquals(1.0f, p.resolveStickScale(game, 1.0f))
        assertEquals(1.2f, p.resolveButtonsScale(game, 1.2f))
        assertEquals(1.1f, p.resolveDpadScale(game, 1.1f))
        assertEquals(mapOf("A" to 1.0f), p.resolveButtonSizeMults(game, mapOf("A" to 1.0f)))
        assertEquals(mapOf("A" to (10f to 20f)), p.resolveButtonOffsets(game, mapOf("A" to (10f to 20f))))
        assertFalse(p.hasControlLayoutOverride(game))
        assertFalse(p.hasProfile(game))
    }

    @Test
    fun layoutPositionsWinOverGlobalAndClear() {
        val p = PerGameSettings(MapStore())
        val stick = ControlPosition(0.2f, 0.8f)
        val buttons = ControlPosition(0.8f, 0.6f)
        p.setStickPositionOverride(game, stick)
        p.setButtonsPositionOverride(game, buttons)
        assertEquals(stick, p.stickPositionOverride(game))
        assertEquals(buttons, p.buttonsPositionOverride(game))
        assertEquals(stick, p.resolveStickPosition(game, ControlPosition(0.14f, 0.72f)))
        assertEquals(buttons, p.resolveButtonsPosition(game, ControlPosition(0.86f, 0.72f)))
        assertTrue(p.hasControlLayoutOverride(game))
        assertTrue(p.hasProfile(game))
        p.setStickPositionOverride(game, null)
        p.setButtonsPositionOverride(game, null)
        assertNull(p.stickPositionOverride(game))
        assertNull(p.buttonsPositionOverride(game))
        assertFalse(p.hasControlLayoutOverride(game))
        assertFalse(p.hasProfile(game))
    }

    @Test
    fun layoutScalesWinOverGlobalAndClear() {
        val p = PerGameSettings(MapStore())
        p.setStickScaleOverride(game, 1.3f)
        p.setButtonsScaleOverride(game, 0.8f)
        assertEquals(1.3f, p.stickScaleOverride(game))
        assertEquals(0.8f, p.buttonsScaleOverride(game))
        assertEquals(1.3f, p.resolveStickScale(game, 1.0f))
        assertEquals(0.8f, p.resolveButtonsScale(game, 1.0f))
        p.setStickScaleOverride(game, null)
        p.setButtonsScaleOverride(game, null)
        assertNull(p.stickScaleOverride(game))
        assertEquals(1.0f, p.resolveStickScale(game, 1.0f))
    }

    @Test
    fun dpadScaleFallsBackToPerGameStickScale() {
        val p = PerGameSettings(MapStore())
        // No override at all: the global value wins.
        assertEquals(1.0f, p.resolveDpadScale(game, 1.0f))
        // A stick-scale override stands in for the D-pad, mirroring
        // ControlSettings' global fallback.
        p.setStickScaleOverride(game, 1.4f)
        assertEquals(1.4f, p.resolveDpadScale(game, 1.0f))
        // An explicit D-pad override wins over both.
        p.setDpadScaleOverride(game, 0.9f)
        assertEquals(0.9f, p.resolveDpadScale(game, 1.0f))
        p.setDpadScaleOverride(game, null)
        assertEquals(1.4f, p.resolveDpadScale(game, 1.0f))
    }

    @Test
    fun perButtonOverridesWinPerButtonAndMergeWithGlobal() {
        val p = PerGameSettings(MapStore())
        val globalSizes = mapOf("A" to 1.0f, "B" to 1.1f)
        val globalOffsets = mapOf("A" to (0f to 0f), "C" to (5f to 5f))
        // Only B's size and A's offset are overridden: the rest
        // keep the global value.
        p.setButtonSizeMultOverride(game, "B", 1.5f)
        p.setButtonOffsetOverride(game, "A", 12f to -8f)
        assertEquals(mapOf("A" to 1.0f, "B" to 1.5f), p.resolveButtonSizeMults(game, globalSizes))
        assertEquals(
            mapOf("A" to (12f to -8f), "C" to (5f to 5f)),
            p.resolveButtonOffsets(game, globalOffsets),
        )
        assertTrue(p.hasControlLayoutOverride(game))
        // Clearing one button restores the global value for it only.
        p.setButtonSizeMultOverride(game, "B", null)
        p.setButtonOffsetOverride(game, "A", null)
        assertEquals(globalSizes, p.resolveButtonSizeMults(game, globalSizes))
        assertEquals(globalOffsets, p.resolveButtonOffsets(game, globalOffsets))
        assertFalse(p.hasControlLayoutOverride(game))
    }

    @Test
    fun layoutOverridesArePerGame() {
        val p = PerGameSettings(MapStore())
        p.setStickPositionOverride(game, ControlPosition(0.2f, 0.8f))
        p.setButtonSizeMultOverride(game, "A", 1.5f)
        assertNull(p.stickPositionOverride(other))
        assertEquals(ControlPosition(0.14f, 0.72f), p.resolveStickPosition(other, ControlPosition(0.14f, 0.72f)))
        assertTrue(p.resolveButtonSizeMults(other, emptyMap()).isEmpty())
        assertFalse(p.hasControlLayoutOverride(other))
        assertFalse(p.hasProfile(other))
    }

    @Test
    fun badStoredLayoutValuesReadAsUnset() {
        val store = MapStore()
        store.map["pergame.$game.controls.stickPos"] = "2.0,0.5"
        store.map["pergame.$game.controls.buttonsPos"] = "0.8"
        store.map["pergame.$game.controls.stickScale"] = "9.9"
        store.map["pergame.$game.controls.dpadScale"] = "big"
        store.map["pergame.$game.controls.buttonSize.A"] = "0.1"
        store.map["pergame.$game.controls.buttonOffset.B"] = "999,0"
        val p = PerGameSettings(store)
        assertNull(p.stickPositionOverride(game))
        assertNull(p.buttonsPositionOverride(game))
        assertNull(p.stickScaleOverride(game))
        assertNull(p.dpadScaleOverride(game))
        assertNull(p.buttonSizeMultOverride(game, "A"))
        assertNull(p.buttonOffsetOverride(game, "B"))
        assertFalse("bad layout values must not count as a profile", p.hasProfile(game))
    }

    @Test
    fun outOfRangeLayoutValuesAreClampedOnWrite() {
        val p = PerGameSettings(MapStore())
        p.setStickPositionOverride(game, ControlPosition(0.001f, 1.5f))
        assertEquals(ControlPosition(0.02f, 0.98f), p.stickPositionOverride(game))
        p.setStickScaleOverride(game, 5.0f)
        assertEquals(1.5f, p.stickScaleOverride(game))
        p.setButtonOffsetOverride(game, "A", 900f to -900f)
        assertEquals(500f to -500f, p.buttonOffsetOverride(game, "A"))
    }

    @Test
    fun clearControlLayoutDropsOnlyTheLayout() {
        val p = PerGameSettings(MapStore())
        p.setCoreOverride(game, "geolith_region", "jp")
        p.setStickPositionOverride(game, ControlPosition(0.2f, 0.8f))
        p.setStickScaleOverride(game, 1.3f)
        p.setButtonSizeMultOverride(game, "A", 1.5f)
        p.setButtonOffsetOverride(game, "B", 10f to 10f)
        assertTrue(p.hasProfile(game))
        p.clearControlLayout(game)
        assertNull(p.stickPositionOverride(game))
        assertNull(p.stickScaleOverride(game))
        assertTrue(p.buttonSizeMultsOverride(game).isEmpty())
        assertTrue(p.buttonOffsetsOverride(game).isEmpty())
        assertFalse(p.hasControlLayoutOverride(game))
        // The core override survives: Reset restores the global
        // layout for this game, it does not wipe the profile.
        assertEquals("jp", p.coreOverride(game, "geolith_region"))
        assertTrue(p.hasProfile(game))
    }

    @Test
    fun clearDropsLayoutOverridesToo() {
        val p = PerGameSettings(MapStore())
        p.setStickPositionOverride(game, ControlPosition(0.2f, 0.8f))
        p.setButtonSizeMultOverride(game, "A", 1.5f)
        p.setButtonOffsetOverride(game, "B", 10f to 10f)
        p.clear(game)
        assertNull(p.stickPositionOverride(game))
        assertTrue(p.buttonSizeMultsOverride(game).isEmpty())
        assertTrue(p.buttonOffsetsOverride(game).isEmpty())
        assertFalse(p.hasProfile(game))
    }

    @Test
    fun unknownButtonIdIsNeverStored() {
        val p = PerGameSettings(MapStore())
        p.setButtonSizeMultOverride(game, "X", 1.5f)
        p.setButtonOffsetOverride(game, "Select", 10f to 10f)
        assertNull(p.buttonSizeMultOverride(game, "X"))
        assertNull(p.buttonOffsetOverride(game, "Select"))
        assertFalse(p.hasControlLayoutOverride(game))
    }
}
