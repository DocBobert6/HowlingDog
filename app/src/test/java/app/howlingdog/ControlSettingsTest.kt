package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the on-screen controls' settings:
 * defaults, round-trips, and invalid values falling back to the
 * defaults. No Android framework calls — a fake in-memory store.
 */
class ControlSettingsTest {

    private class MapStore : OptionStore {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    }

    @Test
    fun defaultsAreLightSixtyMediumAndBottomCorners() {
        val s = ControlSettings(MapStore())
        assertEquals(HapticLevel.LIGHT, s.haptics())
        assertEquals(0.6f, s.opacity(), 0.001f)
        assertEquals(ControlSize.MEDIUM, s.size())
        assertEquals(ControlSettings.DEFAULT_STICK_POS, s.stickPosition())
        assertEquals(ControlSettings.DEFAULT_BUTTONS_POS, s.buttonsPosition())
    }

    @Test
    fun hapticsRoundTripsEveryLevel() {
        val s = ControlSettings(MapStore())
        for (level in HapticLevel.entries) {
            s.setHaptics(level)
            assertEquals(level, s.haptics())
        }
    }

    @Test
    fun opacityRoundTripsAndClamps() {
        val s = ControlSettings(MapStore())
        s.setOpacity(35)
        assertEquals(0.35f, s.opacity(), 0.001f)
        s.setOpacity(100)
        assertEquals(1.0f, s.opacity(), 0.001f)
        // Never fully invisible, never above full.
        s.setOpacity(5)
        assertEquals(0.2f, s.opacity(), 0.001f)
        s.setOpacity(250)
        assertEquals(1.0f, s.opacity(), 0.001f)
    }

    @Test
    fun sizeRoundTripsEverySize() {
        val s = ControlSettings(MapStore())
        for (size in ControlSize.entries) {
            s.setSize(size)
            assertEquals(size, s.size())
        }
    }

    @Test
    fun positionsRoundTripAndResetRestoresDefaults() {
        val s = ControlSettings(MapStore())
        val stick = ControlPosition(0.25f, 0.6f)
        val buttons = ControlPosition(0.75f, 0.65f)
        s.setStickPosition(stick)
        s.setButtonsPosition(buttons)
        assertEquals(stick, s.stickPosition())
        assertEquals(buttons, s.buttonsPosition())
        s.resetLayout()
        assertEquals(ControlSettings.DEFAULT_STICK_POS, s.stickPosition())
        assertEquals(ControlSettings.DEFAULT_BUTTONS_POS, s.buttonsPosition())
    }

    @Test
    fun invalidValuesFallBackToDefaults() {
        val store = MapStore()
        store.put(ControlSettings.KEY_HAPTICS, "banana")
        store.put(ControlSettings.KEY_OPACITY, "not-a-number")
        store.put(ControlSettings.KEY_SIZE, "huge")
        store.put(ControlSettings.KEY_STICK_POS, "0.5")
        store.put(ControlSettings.KEY_BUTTONS_POS, "9.9,9.9")
        val s = ControlSettings(store)
        assertEquals(HapticLevel.LIGHT, s.haptics())
        assertEquals(0.6f, s.opacity(), 0.001f)
        assertEquals(ControlSize.MEDIUM, s.size())
        assertEquals(ControlSettings.DEFAULT_STICK_POS, s.stickPosition())
        assertEquals(ControlSettings.DEFAULT_BUTTONS_POS, s.buttonsPosition())
    }

    @Test
    fun sizesAreOrderedSmallMediumLarge() {
        assertTrue(ControlSize.SMALL.scale < ControlSize.MEDIUM.scale)
        assertTrue(ControlSize.MEDIUM.scale < ControlSize.LARGE.scale)
        assertEquals(1.0f, ControlSize.MEDIUM.scale, 0.001f)
    }
}
