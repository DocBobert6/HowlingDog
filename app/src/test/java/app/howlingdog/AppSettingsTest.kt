package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun setupScreenUnseenByDefault() {
        assertFalse(settingsWith().setupSeen())
    }

    @Test
    fun setupSeenSticksOnceSet() {
        val settings = settingsWith()
        settings.setSetupSeen()
        assertTrue(settings.setupSeen())
        assertTrue(settingsWith(AppSettings.KEY_SETUP_SEEN to "yes").setupSeen())
        assertFalse(settingsWith(AppSettings.KEY_SETUP_SEEN to "no").setupSeen())
    }

    @Test
    fun dismissedUpdateEmptyByDefault() {
        assertNull(settingsWith().dismissedUpdateVersion())
        assertNull(settingsWith(AppSettings.KEY_DISMISSED_UPDATE to "").dismissedUpdateVersion())
    }

    @Test
    fun dismissedUpdateRoundTripsAndClears() {
        val settings = settingsWith()
        settings.setDismissedUpdateVersion("v1.1.0")
        assertEquals("v1.1.0", settings.dismissedUpdateVersion())
        settings.clearDismissedUpdateVersion()
        assertNull(settings.dismissedUpdateVersion())
    }

    @Test
    fun lastUpdateCheckDefaultsToZeroAndRoundTrips() {
        val settings = settingsWith()
        assertEquals(0L, settings.lastUpdateCheck())
        settings.setLastUpdateCheck(123456789L)
        assertEquals(123456789L, settings.lastUpdateCheck())
        assertEquals(0L, settingsWith(AppSettings.KEY_LAST_UPDATE_CHECK to "junk").lastUpdateCheck())
    }
}
