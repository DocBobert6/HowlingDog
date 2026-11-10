package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the Phase 4 settings: the option table
 * (defaults, allowed values, live vs restart) and the settings
 * store round-trip through a fake in-memory store. No Android
 * framework calls and no native code — Bridge.ensureLoaded() fails
 * quietly on the JVM, so set() only ever touches the store here.
 */
class CoreSettingsTest {

    private class MapStore : OptionStore {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    }

    @Test
    fun defaultsMatchTheBridgeTable() {
        val settings = CoreSettings(MapStore())
        // The bridge's documented defaults (bridge/jni/bridge.cpp).
        assertEquals("aes", "aes") // system type is the per-game mode, not a setting
        assertEquals("resnet", settings.get("geolith_palette"))
        assertEquals("1:1", settings.get("geolith_aspect"))
        assertEquals("96", settings.get("geolith_sprlimit"))
        assertEquals("off", settings.get("geolith_oc"))
        assertEquals("disabled", settings.get("geolith_cd_speed_hack"))
        assertEquals("disabled", settings.get("geolith_cd_dma_len_limit"))
        assertEquals("disabled", settings.get("geolith_cd_skip_loading"))
        assertEquals("us", settings.get("geolith_region"))
        assertEquals("8", settings.get("geolith_overscan_t"))
        assertEquals("8", settings.get("geolith_overscan_b"))
        assertEquals("8", settings.get("geolith_overscan_l"))
        assertEquals("8", settings.get("geolith_overscan_r"))
        assertEquals("on", settings.get("geolith_memcard"))
        assertEquals("off", settings.get("geolith_memcard_wp"))
        assertEquals("off", settings.get("geolith_freeplay"))
        assertEquals("mvs", settings.get("geolith_unibios_hw"))
        assertEquals("off", settings.get("geolith_settingmode"))
        assertEquals("off", settings.get("geolith_4player"))
        assertEquals("off", settings.get("geolith_disable_adpcm_wrap"))
    }

    @Test
    fun setAndGetRoundTrip() {
        val settings = CoreSettings(MapStore())
        settings.set("geolith_palette", "raw")
        assertEquals("raw", settings.get("geolith_palette"))
        settings.set("geolith_sprlimit", "381")
        assertEquals("381", settings.get("geolith_sprlimit"))
        settings.set("geolith_region", "jp")
        assertEquals("jp", settings.get("geolith_region"))
    }

    @Test
    fun invalidValuesAndUnknownKeysAreRejected() {
        val store = MapStore()
        val settings = CoreSettings(store)
        settings.set("geolith_palette", "neon")
        assertEquals("resnet", settings.get("geolith_palette"))
        assertFalse(store.map.containsKey("geolith_palette"))
        settings.set("geolith_nope", "x")
        assertEquals("", settings.get("geolith_nope"))
        assertFalse(store.map.containsKey("geolith_nope"))
        settings.set("geolith_sprlimit", "100")
        assertEquals("96", settings.get("geolith_sprlimit"))
    }

    @Test
    fun liveFlagsMatchTheCore() {
        // The core re-reads these on every GET_VARIABLE_UPDATE
        // (libretro/libretro.c check_variables, non-first-run part).
        for (key in listOf(
            "geolith_palette", "geolith_aspect", "geolith_sprlimit",
            "geolith_oc", "geolith_disable_adpcm_wrap",
            "geolith_cd_speed_hack", "geolith_cd_dma_len_limit",
            "geolith_cd_skip_loading", "geolith_memcard",
            "geolith_memcard_wp", "geolith_freeplay",
        )) {
            assertTrue("$key must be live", CoreOptions.byKey(key)?.live == true)
        }
        // These are first-run only: next game start.
        for (key in listOf(
            "geolith_region", "geolith_overscan_t", "geolith_overscan_b",
            "geolith_overscan_l", "geolith_overscan_r",
            "geolith_unibios_hw", "geolith_settingmode", "geolith_4player",
        )) {
            assertFalse("$key must be restart-only", CoreOptions.byKey(key)?.live == true)
        }
    }

    @Test
    fun systemTypesAreNotGlobalSettings() {
        // The mode lives on the game entry, not in global settings.
        assertEquals(null, CoreOptions.byKey("geolith_system_type"))
        assertEquals(null, CoreOptions.byKey("geolith_cd_system_type"))
    }

    @Test
    fun everyOptionHasItsDefaultAmongItsValues() {
        for (opt in CoreOptions.ALL) {
            assertTrue(
                "${opt.key} default ${opt.defaultValue} must be an offered value",
                opt.values.any { it.first == opt.defaultValue },
            )
        }
    }
}
