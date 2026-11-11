package app.howlingdog

import android.view.KeyEvent
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
    fun scalesDefaultToOne() {
        val s = ControlSettings(MapStore())
        assertEquals(1.0f, s.stickScale(), 0.001f)
        assertEquals(1.0f, s.buttonsScale(), 0.001f)
    }

    @Test
    fun scalesRoundTripAndClamp() {
        val s = ControlSettings(MapStore())
        s.setStickScale(1.3f)
        s.setButtonsScale(0.8f)
        assertEquals(1.3f, s.stickScale(), 0.001f)
        assertEquals(0.8f, s.buttonsScale(), 0.001f)
        // The handle's range is 0.7x to 1.5x of the default.
        s.setStickScale(0.2f)
        assertEquals(0.7f, s.stickScale(), 0.001f)
        s.setButtonsScale(9.0f)
        assertEquals(1.5f, s.buttonsScale(), 0.001f)
    }

    @Test
    fun scalesResetWithTheLayout() {
        val s = ControlSettings(MapStore())
        s.setStickScale(1.4f)
        s.setButtonsScale(0.75f)
        s.resetLayout()
        assertEquals(1.0f, s.stickScale(), 0.001f)
        assertEquals(1.0f, s.buttonsScale(), 0.001f)
    }

    @Test
    fun leftControlDefaultsToStickAndRoundTrips() {
        val s = ControlSettings(MapStore())
        assertEquals(LeftControl.STICK, s.leftControl())
        s.setLeftControl(LeftControl.DPAD)
        assertEquals(LeftControl.DPAD, s.leftControl())
        s.setLeftControl(LeftControl.STICK)
        assertEquals(LeftControl.STICK, s.leftControl())
    }

    @Test
    fun invalidLeftControlFallsBackToStick() {
        val store = MapStore()
        store.put(ControlSettings.KEY_LEFT_CONTROL, "joystick")
        val s = ControlSettings(store)
        assertEquals(LeftControl.STICK, s.leftControl())
    }

    @Test
    fun buttonOffsetsDefaultToNone() {
        val s = ControlSettings(MapStore())
        assertEquals(emptyMap<String, Pair<Float, Float>>(), s.buttonOffsets())
        assertEquals(null, s.buttonOffset("A"))
    }

    @Test
    fun buttonOffsetsRoundTripAndClamp() {
        val s = ControlSettings(MapStore())
        s.setButtonOffset("A", -132f to -62f)
        s.setButtonOffset("Coin", 10.5f to 116f)
        assertEquals(-132f to -62f, s.buttonOffset("A"))
        assertEquals(10.5f to 116f, s.buttonOffset("Coin"))
        assertEquals(
            mapOf("A" to (-132f to -62f), "Coin" to (10.5f to 116f)),
            s.buttonOffsets(),
        )
        // Offsets stay inside the game area.
        s.setButtonOffset("B", 900f to -900f)
        assertEquals(500f to -500f, s.buttonOffset("B"))
    }

    @Test
    fun buttonOffsetsResetWithTheLayout() {
        val s = ControlSettings(MapStore())
        s.setButtonOffset("A", -132f to -62f)
        s.setButtonOffset("D", 96f to -24f)
        s.resetLayout()
        assertEquals(emptyMap<String, Pair<Float, Float>>(), s.buttonOffsets())
        assertEquals(null, s.buttonOffset("A"))
    }

    @Test
    fun invalidButtonOffsetsFallBackToNull() {
        val store = MapStore()
        store.put(ControlSettings.KEY_BUTTON_OFFSET_PREFIX + "A", "")
        store.put(ControlSettings.KEY_BUTTON_OFFSET_PREFIX + "B", "not-a-number,5")
        store.put(ControlSettings.KEY_BUTTON_OFFSET_PREFIX + "C", "10")
        store.put(ControlSettings.KEY_BUTTON_OFFSET_PREFIX + "D", "9999,0")
        store.put(ControlSettings.KEY_BUTTON_OFFSET_PREFIX + "Coin", "1,2,3")
        val s = ControlSettings(store)
        assertEquals(null, s.buttonOffset("A"))
        assertEquals(null, s.buttonOffset("B"))
        assertEquals(null, s.buttonOffset("C"))
        assertEquals(null, s.buttonOffset("D"))
        assertEquals(null, s.buttonOffset("Coin"))
        assertEquals(emptyMap<String, Pair<Float, Float>>(), s.buttonOffsets())
    }

    @Test
    fun invalidValuesFallBackToDefaults() {
        val store = MapStore()
        store.put(ControlSettings.KEY_HAPTICS, "banana")
        store.put(ControlSettings.KEY_OPACITY, "not-a-number")
        store.put(ControlSettings.KEY_SIZE, "huge")
        store.put(ControlSettings.KEY_STICK_POS, "0.5")
        store.put(ControlSettings.KEY_BUTTONS_POS, "9.9,9.9")
        store.put(ControlSettings.KEY_STICK_SCALE, "not-a-number")
        store.put(ControlSettings.KEY_BUTTONS_SCALE, "3.0")
        val s = ControlSettings(store)
        assertEquals(HapticLevel.LIGHT, s.haptics())
        assertEquals(0.6f, s.opacity(), 0.001f)
        assertEquals(ControlSize.MEDIUM, s.size())
        assertEquals(ControlSettings.DEFAULT_STICK_POS, s.stickPosition())
        assertEquals(ControlSettings.DEFAULT_BUTTONS_POS, s.buttonsPosition())
        assertEquals(1.0f, s.stickScale(), 0.001f)
        assertEquals(1.0f, s.buttonsScale(), 0.001f)
    }

    @Test
    fun turboDefaultsToOffForEveryButton() {
        val s = ControlSettings(MapStore())
        for (id in ControlSettings.TURBO_BUTTON_IDS) {
            assertEquals(false, s.turboEnabled(id))
        }
        assertEquals(0, s.turboMask())
    }

    @Test
    fun turboRoundTripsPerButton() {
        val s = ControlSettings(MapStore())
        s.setTurboEnabled("A", true)
        s.setTurboEnabled("C", true)
        assertEquals(true, s.turboEnabled("A"))
        assertEquals(false, s.turboEnabled("B"))
        assertEquals(true, s.turboEnabled("C"))
        assertEquals(false, s.turboEnabled("D"))
        assertEquals(InputMask.neoA() or InputMask.neoC(), s.turboMask())
        s.setTurboEnabled("A", false)
        assertEquals(false, s.turboEnabled("A"))
        assertEquals(InputMask.neoC(), s.turboMask())
    }

    @Test
    fun turboIgnoresUnknownButtonsAndBadValues() {
        val store = MapStore()
        store.put(ControlSettings.KEY_TURBO_PREFIX + "A", "yes")
        store.put(ControlSettings.KEY_TURBO_PREFIX + "B", "ON")
        val s = ControlSettings(store)
        assertEquals(false, s.turboEnabled("A"))
        assertEquals(false, s.turboEnabled("B"))
        assertEquals(false, s.turboEnabled("Coin"))
        assertEquals(false, s.turboEnabled("Start"))
        assertEquals(0, s.turboMask())
        // Setting an unknown button stores nothing.
        s.setTurboEnabled("Coin", true)
        assertEquals(false, s.turboEnabled("Coin"))
        assertEquals(0, s.turboMask())
    }

    @Test
    fun sizesAreOrderedSmallMediumLarge() {
        assertTrue(ControlSize.SMALL.scale < ControlSize.MEDIUM.scale)
        assertTrue(ControlSize.MEDIUM.scale < ControlSize.LARGE.scale)
        assertEquals(1.0f, ControlSize.MEDIUM.scale, 0.001f)
    }

    // Part 2 — per-group opacity: the stick/D-pad and the buttons
    // each have their own, 20–100%, default 60.

    @Test
    fun perGroupOpacityDefaultsToSixty() {
        val s = ControlSettings(MapStore())
        assertEquals(0.6f, s.stickOpacity(), 0.001f)
        assertEquals(0.6f, s.buttonsOpacity(), 0.001f)
    }

    @Test
    fun perGroupOpacityFallsBackToTheLegacyOpacity() {
        // A user who already set the old whole-overlay opacity
        // keeps it for both groups until they move a slider.
        val store = MapStore()
        store.put(ControlSettings.KEY_OPACITY, "80")
        val s = ControlSettings(store)
        assertEquals(0.8f, s.stickOpacity(), 0.001f)
        assertEquals(0.8f, s.buttonsOpacity(), 0.001f)
        // Moving one slider leaves the other on the legacy value.
        s.setStickOpacity(40)
        assertEquals(0.4f, s.stickOpacity(), 0.001f)
        assertEquals(0.8f, s.buttonsOpacity(), 0.001f)
    }

    @Test
    fun perGroupOpacityRoundTripsAndClamps() {
        val s = ControlSettings(MapStore())
        s.setStickOpacity(35)
        s.setButtonsOpacity(90)
        assertEquals(0.35f, s.stickOpacity(), 0.001f)
        assertEquals(0.9f, s.buttonsOpacity(), 0.001f)
        s.setStickOpacity(5)
        s.setButtonsOpacity(250)
        assertEquals(0.2f, s.stickOpacity(), 0.001f)
        assertEquals(1.0f, s.buttonsOpacity(), 0.001f)
    }

    // Part 2 — the D-pad's own scale: sized separately from the
    // stick, falling back to the stick's scale when never set.

    @Test
    fun dpadScaleFallsBackToTheStickScale() {
        val s = ControlSettings(MapStore())
        s.setStickScale(1.3f)
        assertEquals(1.3f, s.dpadScale(), 0.001f)
        // Once set, it is independent of the stick's scale.
        s.setDpadScale(0.8f)
        s.setStickScale(1.0f)
        assertEquals(0.8f, s.dpadScale(), 0.001f)
    }

    @Test
    fun dpadScaleRoundTripsAndClamps() {
        val s = ControlSettings(MapStore())
        s.setDpadScale(1.5f)
        assertEquals(1.5f, s.dpadScale(), 0.001f)
        s.setDpadScale(0.2f)
        assertEquals(0.7f, s.dpadScale(), 0.001f)
        s.setDpadScale(9.0f)
        assertEquals(1.5f, s.dpadScale(), 0.001f)
    }

    // Part 2 — per-button size multipliers: one button's own size
    // relative to the group size (1.0 = the group size).

    @Test
    fun buttonSizeMultDefaultsToOne() {
        val s = ControlSettings(MapStore())
        assertEquals(1.0f, s.buttonSizeMult("A"), 0.001f)
        assertEquals(1.0f, s.buttonSizeMult("Coin"), 0.001f)
        assertTrue(s.buttonSizeMults().isEmpty())
    }

    @Test
    fun buttonSizeMultRoundTripsAndClamps() {
        val s = ControlSettings(MapStore())
        s.setButtonSizeMult("A", 1.5f)
        s.setButtonSizeMult("Coin", 0.7f)
        assertEquals(1.5f, s.buttonSizeMult("A"), 0.001f)
        assertEquals(0.7f, s.buttonSizeMult("Coin"), 0.001f)
        assertEquals(1.0f, s.buttonSizeMult("B"), 0.001f)
        assertEquals(mapOf("A" to 1.5f, "Coin" to 0.7f), s.buttonSizeMults())
        s.setButtonSizeMult("A", 0.2f)
        assertEquals(0.7f, s.buttonSizeMult("A"), 0.001f)
        s.setButtonSizeMult("A", 9.0f)
        assertEquals(1.5f, s.buttonSizeMult("A"), 0.001f)
    }

    @Test
    fun buttonSizeMultIgnoresUnknownButtons() {
        val s = ControlSettings(MapStore())
        s.setButtonSizeMult("Z", 1.5f)
        assertEquals(1.0f, s.buttonSizeMult("Z"), 0.001f)
        assertTrue(s.buttonSizeMults().isEmpty())
    }

    @Test
    fun buttonSizeMultsResetWithTheLayout() {
        val s = ControlSettings(MapStore())
        s.setButtonSizeMult("A", 1.4f)
        s.setDpadScale(0.8f)
        s.resetLayout()
        assertEquals(1.0f, s.buttonSizeMult("A"), 0.001f)
        assertTrue(s.buttonSizeMults().isEmpty())
        assertEquals(1.0f, s.dpadScale(), 0.001f)
    }

    @Test
    fun gamepadKeycodeRoundTrip() {
        val s = ControlSettings(MapStore())
        // Defaults first: exactly today's mapping.
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, s.gamepadKeycode("A"))
        assertEquals(KeyEvent.KEYCODE_BUTTON_SELECT, s.gamepadKeycode("Coin"))
        // A swap is stored by name and read back.
        s.setGamepadMapping(
            GamepadMapping.assign(GamepadMapping.DEFAULT, "A", KeyEvent.KEYCODE_BUTTON_X),
        )
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, s.gamepadKeycode("A"))
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, s.gamepadKeycode("C"))
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, s.gamepadMapping().getValue("A"))
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, s.gamepadMapping().getValue("C"))
    }

    @Test
    fun gamepadMappingResetRestoresDefaults() {
        val s = ControlSettings(MapStore())
        s.setGamepadKeycode("A", KeyEvent.KEYCODE_BUTTON_Y)
        s.resetGamepadMapping()
        assertEquals(GamepadMapping.DEFAULT, s.gamepadMapping())
    }

    @Test
    fun gamepadKeycodeIgnoresUnknownIdsAndShoulders() {
        val s = ControlSettings(MapStore())
        s.setGamepadKeycode("Z", KeyEvent.KEYCODE_BUTTON_X)
        s.setGamepadKeycode("A", KeyEvent.KEYCODE_BUTTON_L1)
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, s.gamepadKeycode("A"))
    }
}
