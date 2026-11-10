package app.howlingdog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the app-level settings (boot splash toggle). */
class AppSettingsTest {

    private fun settingsWith(vararg pairs: Pair<String, String>): AppSettings {
        val map = mutableMapOf<String, String>()
        map.putAll(pairs)
        val store = object : OptionStore {
            override fun get(key: String): String? = map[key]
            override fun put(key: String, value: String) {
                map[key] = value
            }
        }
        return AppSettings(store)
    }

    @Test
    fun bootScreenOnByDefault() {
        assertTrue(settingsWith().showBoot())
    }

    @Test
    fun bootScreenToggleRoundTrips() {
        val settings = settingsWith()
        settings.setShowBoot(false)
        assertFalse(settings.showBoot())
        settings.setShowBoot(true)
        assertTrue(settings.showBoot())
    }

    @Test
    fun invalidBootValueFallsBackToDefault() {
        assertTrue(settingsWith(AppSettings.KEY_SHOW_BOOT to "maybe").showBoot())
    }
}
